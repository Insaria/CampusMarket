package edu.bistu.campusmarket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.bistu.campusmarket.config.PasswordConfig;
import edu.bistu.campusmarket.controller.RegistrationController;
import edu.bistu.campusmarket.exception.GlobalApiExceptionHandler;
import edu.bistu.campusmarket.mapper.UserRegistrationMapper;
import edu.bistu.campusmarket.mapper.UserRegistrationMapper.NewUser;
import edu.bistu.campusmarket.security.BankAccountEncryption;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RegistrationTest {
    private static final String PASSWORD = "test-only-pass";
    private static final String BANK_ACCOUNT = "0000000000000001";
    private static final byte[] TEST_KEY = new byte[32];
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final PasswordEncoder ENCODER = new PasswordConfig().passwordEncoder();
    private UserRegistrationMapper mapper;
    private Clock clock;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mapper = mock(UserRegistrationMapper.class);
        clock = mock(Clock.class);
        when(clock.instant()).thenReturn(NOW);
        mvc = createMvc(Base64.getEncoder().encodeToString(TEST_KEY));
    }

    @Test
    void registersPendingUserUsingHashAndAuthenticatedEncryption() throws Exception {
        when(mapper.insert(any())).thenReturn(1);
        submit(issue(), request("  Campus_01 ", PASSWORD, BANK_ACCOUNT, "222222"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.registrationStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.bankAccount").doesNotExist())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain(PASSWORD, BANK_ACCOUNT));

        ArgumentCaptor<NewUser> inserted = ArgumentCaptor.forClass(NewUser.class);
        verify(mapper).insert(inserted.capture());
        NewUser user = inserted.getValue();
        assertThat(user.loginName()).isEqualTo("campus_01");
        assertThat(user.email()).isEqualTo("Test@example.com");
        assertThat(user.passwordHash()).startsWith("{pbkdf2-sha256-v1}").isNotEqualTo(PASSWORD);
        assertThat(ENCODER.matches(PASSWORD, user.passwordHash())).isTrue();
        byte[] envelope = user.bankAccountEncrypted();
        assertThat(envelope).hasSize(45);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(TEST_KEY, "AES"),
                new GCMParameterSpec(128, Arrays.copyOfRange(envelope, 1, 13)));
        cipher.updateAAD(new byte[]{envelope[0]});
        assertThat(new String(cipher.doFinal(envelope, 13, envelope.length - 13), StandardCharsets.US_ASCII))
                .isEqualTo(BANK_ACCOUNT);

        Configuration configuration = new Configuration();
        configuration.addMapper(UserRegistrationMapper.class);
        String sql = configuration.getMappedStatement(UserRegistrationMapper.class.getName() + ".insert")
                .getBoundSql(user).getSql();
        assertThat(sql).contains("'USER', 'PENDING', 'ENABLED'").doesNotContain(BANK_ACCOUNT, PASSWORD);
    }

    @Test
    void rejectsDuplicateLoginName() throws Exception {
        when(mapper.loginNameExists("campus_01")).thenReturn(true);
        submit(issue(), request("Campus_01", PASSWORD, BANK_ACCOUNT, "222222"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LOGIN_NAME_TAKEN"));
        verify(mapper).loginNameExists("campus_01");
        org.mockito.Mockito.verifyNoMoreInteractions(mapper);
    }

    @Test
    void translatesUniqueConstraintRace() throws Exception {
        when(mapper.insert(any())).thenThrow(new DuplicateKeyException("private details"));
        submit(issue(), request("Campus_01", PASSWORD, BANK_ACCOUNT, "222222"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LOGIN_NAME_TAKEN"));
    }

    @ParameterizedTest
    @CsvSource({"a b,test-only-pass,0000000000000001", "valid,short,0000000000000001",
            "valid,test-only-pass,000000000000000A", "valid,test-only-pass,０００００００００００００００１"})
    void rejectsInvalidRegistrationFields(String name, String password, String bank) throws Exception {
        submit(issue(), request(name, password, bank, "222222"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(mapper);
    }

    @Test
    void consumesWrongCaptchaAndRejectsItsReuse() throws Exception {
        MockHttpSession session = issue();
        assertInvalidCaptcha(session, "333333");
        assertInvalidCaptcha(session, "222222");
        verifyNoInteractions(mapper);
    }

    @Test
    void rejectsCaptchaInAnotherSessionAndAtExpiration() throws Exception {
        MockHttpSession original = issue();
        assertInvalidCaptcha(new MockHttpSession(), "222222");
        when(clock.instant()).thenReturn(NOW.plusSeconds(120));
        assertInvalidCaptcha(original, "222222");
        verifyNoInteractions(mapper);
    }

    @Test
    void consumesSuccessfulCaptchaAndIgnoresPrivilegeFields() throws Exception {
        when(mapper.insert(any())).thenReturn(1);
        MockHttpSession session = issue();
        String body = request("campus_01", PASSWORD, BANK_ACCOUNT, "222222");
        submit(session, body.substring(0, body.length() - 1) + ",\"roleCode\":\"ADMIN\",\"accountStatus\":\"DELETED\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.registrationStatus").value("PENDING"));
        assertInvalidCaptcha(session, "222222");
        verify(mapper).insert(any());
    }

    @Test
    void missingEncryptionKeyPreventsInsert() throws Exception {
        mvc = createMvc("");
        submit(issue(), request("campus_01", PASSWORD, BANK_ACCOUNT, "222222"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REGISTRATION_UNAVAILABLE"));
        verify(mapper).loginNameExists("campus_01");
        org.mockito.Mockito.verifyNoMoreInteractions(mapper);
    }

    private MockMvc createMvc(String key) {
        SecureRandom random = mock(SecureRandom.class);
        RegistrationCaptcha captcha = new RegistrationCaptcha(clock, random);
        var service = new UserRegistrationService(captcha, mapper, ENCODER, new BankAccountEncryption(key));
        return MockMvcBuilders.standaloneSetup(new RegistrationController(captcha, service))
                .setControllerAdvice(new GlobalApiExceptionHandler()).build();
    }

    private MockHttpSession issue() throws Exception {
        var result = mvc.perform(get("/api/auth/captcha"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        assertThat(ImageIO.read(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))).isNotNull();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private void assertInvalidCaptcha(MockHttpSession session, String answer) throws Exception {
        submit(session, request("campus_01", PASSWORD, BANK_ACCOUNT, answer))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CAPTCHA"));
    }

    private ResultActions submit(MockHttpSession session, String body) throws Exception {
        return mvc.perform(post("/api/auth/register").session(session).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String request(String name, String password, String bank, String captcha) {
        return """
                {"loginName":"%s","password":"%s","realName":"Test User","phone":"+8613800000000",
                 "email":"Test@EXAMPLE.COM","city":"Beijing","genderCode":"OTHER","bankAccount":"%s","captcha":"%s"}
                """.formatted(name, password, bank, captcha);
    }
}
