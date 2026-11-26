package edu.bistu.campusmarket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import edu.bistu.campusmarket.config.ApiAccessConfig;
import edu.bistu.campusmarket.controller.SellerController;
import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.dto.SellerDtos.SellerView;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.AccountAccessMapper;
import edu.bistu.campusmarket.mapper.AccountAccessMapper.AccountAccess;
import edu.bistu.campusmarket.mapper.SellerMapper;
import edu.bistu.campusmarket.mapper.SellerMapper.Applicant;
import edu.bistu.campusmarket.security.CsrfTokens;
import edu.bistu.campusmarket.security.CurrentUserService;
import edu.bistu.campusmarket.service.PrivateSellerDocuments;
import edu.bistu.campusmarket.service.SellerService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

@WebMvcTest(SellerController.class)
@ActiveProfiles("db")
@Import({ApiAccessConfig.class, CurrentUserService.class, SellerService.class, SellerTest.Transactions.class})
class SellerTest {
    private static final String SHOP = "{\"shopName\":\"Campus store\"}";

    @Autowired private MockMvc mvc;
    @MockitoBean private AccountAccessMapper access;
    @MockitoBean private SellerMapper mapper;
    @MockitoBean private PrivateSellerDocuments documents;

    @Test
    void createsPersonalWithoutCredentialsOrLevelAndWithZeroFee() throws Exception {
        var session = session("USER", "APPROVED", "ENABLED");
        when(mapper.insertPersonal(17, "Campus store", null)).thenReturn(1);
        when(mapper.findByUserId(17)).thenReturn(null, view("PERSONAL", "APPROVED", "ACTIVE", BigDecimal.ZERO));
        mvc.perform(post("/api/sellers/personal").session(session).header(CsrfTokens.HEADER_NAME, token(session))
                        .contentType(MediaType.APPLICATION_JSON).content(SHOP))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.sellerId").value(9))
                .andExpect(jsonPath("$.data.sellerType").value("PERSONAL"))
                .andExpect(jsonPath("$.data.levelCode").doesNotExist())
                .andExpect(jsonPath("$.data.feeRate").value(0));
        verifyNoInteractions(documents);
    }

    @ParameterizedTest
    @CsvSource({"true,false", "false,true"})
    void requiresBothBusinessFiles(boolean license, boolean identity) throws Exception {
        var session = session("USER", "APPROVED", "ENABLED");
        mvc.perform(business(license, identity).session(session).header(CsrfTokens.HEADER_NAME, token(session)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(mapper, documents);
    }

    @Test
    void createsBusinessAsPendingAndInactiveWithoutExposingDocuments() throws Exception {
        var session = session("USER", "APPROVED", "ENABLED");
        when(documents.store(any())).thenReturn("seller-1.png", "seller-2.png");
        when(mapper.insertBusiness(17, "Campus store", null, "seller-1.png", "seller-2.png")).thenReturn(1);
        when(mapper.findByUserId(17)).thenReturn(null, view("BUSINESS", "PENDING", "INACTIVE", null));
        mvc.perform(business(true, true).session(session).header(CsrfTokens.HEADER_NAME, token(session)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.operationStatus").value("INACTIVE"))
                .andExpect(jsonPath("$.data.levelCode").doesNotExist())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain("seller-"));
        verify(documents, never()).discard(any());
    }

    @ParameterizedTest
    @CsvSource({"USER,PENDING,ENABLED", "USER,APPROVED,DISABLED", "ADMIN,APPROVED,ENABLED"})
    void rejectsIneligibleApplicants(String role, String review, String statusValue) throws Exception {
        var session = session(role, review, statusValue);
        mvc.perform(post("/api/sellers/personal").session(session).header(CsrfTokens.HEADER_NAME, token(session))
                        .contentType(MediaType.APPLICATION_JSON).content(SHOP)).andExpect(status().isForbidden());
        verifyNoInteractions(mapper, documents);
    }

    @Test
    void rejectsDuplicateAcrossSellerTypes() throws Exception {
        var session = session("USER", "APPROVED", "ENABLED");
        when(mapper.findByUserId(17)).thenReturn(view("PERSONAL", "APPROVED", "ACTIVE", BigDecimal.ZERO));
        mvc.perform(business(true, true).session(session).header(CsrfTokens.HEADER_NAME, token(session)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELLER_ALREADY_EXISTS"));
        verifyNoInteractions(documents);
    }

    @Test
    void incompleteUserProfilePreventsBusinessUpload() throws Exception {
        var session = session("USER", "APPROVED", "ENABLED");
        when(mapper.lockApplicant(17)).thenReturn(new Applicant(17L, "USER", "APPROVED", "ENABLED", 0));
        mvc.perform(business(true, true).session(session).header(CsrfTokens.HEADER_NAME, token(session)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELLER_PROFILE_INCOMPLETE"));
        verifyNoInteractions(documents);
    }

    @Test
    void discardsFirstDocumentWhenSecondUploadFails() throws Exception {
        var session = session("USER", "APPROVED", "ENABLED");
        when(documents.store(any())).thenReturn("seller-1.png").thenThrow(
                new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_IMAGE", "Invalid image"));
        mvc.perform(business(true, true).session(session).header(CsrfTokens.HEADER_NAME, token(session)))
                .andExpect(status().isBadRequest());
        verify(documents).discard("seller-1.png");
    }

    @Test
    void discardsBothDocumentsOnDatabaseFailure() throws Exception {
        var session = session("USER", "APPROVED", "ENABLED");
        when(documents.store(any())).thenReturn("seller-1.png", "seller-2.png");
        when(mapper.insertBusiness(17, "Campus store", null, "seller-1.png", "seller-2.png"))
                .thenThrow(new DataIntegrityViolationException("test insert failure"));
        mvc.perform(business(true, true).session(session).header(CsrfTokens.HEADER_NAME, token(session)))
                .andExpect(status().isConflict());
        verify(documents).discard("seller-1.png");
        verify(documents).discard("seller-2.png");
    }

    @ParameterizedTest
    @CsvSource({"PERSONAL,", "BUSINESS,1"})
    void bothSellerTypesCanUseProductAccessGuard(String type, Integer level) throws Exception {
        var session = session("USER", "APPROVED", "ENABLED");
        when(access.findByUserId(17L)).thenReturn(
                new AccountAccess(17L, "USER", "APPROVED", "ENABLED", 9L, "APPROVED", "ACTIVE", level, type));
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setSession(session);
        var probe = new ProductGuardProbe();
        var method = new org.springframework.web.method.HandlerMethod(probe, ProductGuardProbe.class.getMethod("publish"));
        assertThat(new edu.bistu.campusmarket.security.ApiAccessInterceptor(new CurrentUserService(access))
                .preHandle(request, new org.springframework.mock.web.MockHttpServletResponse(), method)).isTrue();
        Configuration configuration = new Configuration();
        configuration.addMapper(SellerMapper.class);
        assertThat(configuration.getMappedStatement(SellerMapper.class.getName() + ".findByUserId")
                .getBoundSql(17L).getSql()).contains("m.id AS seller_id", "THEN 0.000000");
    }

    @Test
    void privateStorageChecksBytesAndIgnoresClientFileName(@TempDir Path directory) throws Exception {
        var storage = new PrivateSellerDocuments(directory.toString());
        byte[] payload = png();
        MockMultipartFile upload = new MockMultipartFile("photo", "../../outside.png", "text/plain", payload);
        String first = storage.store(upload);
        String second = storage.store(upload);
        assertThat(first).matches("seller-[0-9]+\\.png").isNotEqualTo(second);
        assertThat(directory.resolve(first)).exists();
        assertThatThrownBy(() -> storage.store(new MockMultipartFile("photo", "fake.png", "image/png", new byte[]{1})))
                .isInstanceOf(BusinessException.class);
        storage.discard(first);
        assertThat(directory.resolve(first)).doesNotExist();
        assertThat(directory.resolve(second)).exists();
    }

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder business(
            boolean license, boolean identity) throws Exception {
        var request = multipart("/api/sellers/business").file(
                new MockMultipartFile("application", "", "application/json", SHOP.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (license) {
            request.file(new MockMultipartFile("businessLicense", "license.png", "image/png", png()));
        }
        if (identity) {
            request.file(new MockMultipartFile("identityDocument", "identity.png", "image/png", png()));
        }
        return request;
    }

    private byte[] png() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }

    private MockHttpSession session(String role, String review, String state) {
        when(access.findByUserId(17L)).thenReturn(new AccountAccess(17L, role, review, state, null, null, null, null, null));
        when(mapper.lockApplicant(17)).thenReturn(new Applicant(17L, role, review, state, 1));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AuthenticatedUser.SESSION_ATTRIBUTE, new AuthenticatedUser(17L, role));
        return session;
    }

    private String token(MockHttpSession session) {
        return CsrfTokens.getOrCreate(session);
    }

    private SellerView view(String type, String review, String state, BigDecimal fee) {
        return new SellerView(9L, type, "Campus store", null, review, state, null, null, fee, null);
    }

    static class ProductGuardProbe {
        @edu.bistu.campusmarket.security.ApiAccess(edu.bistu.campusmarket.security.ApiAccess.Requirement.MERCHANT)
        public void publish() {
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions {
        @Bean
        AbstractPlatformTransactionManager transactionManager() {
            return new AbstractPlatformTransactionManager() {
                @Override protected Object doGetTransaction() { return new Object(); }
                @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
                @Override protected void doCommit(DefaultTransactionStatus status) { }
                @Override protected void doRollback(DefaultTransactionStatus status) { }
            };
        }
    }
}
