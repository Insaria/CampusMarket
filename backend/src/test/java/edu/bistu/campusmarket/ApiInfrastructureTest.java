package edu.bistu.campusmarket;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.bistu.campusmarket.common.ApiResponse;
import edu.bistu.campusmarket.exception.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("standalone")
@Import(ApiInfrastructureTest.TestController.class)
class ApiInfrastructureTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ApplicationContext context;

    @Test
    void keepsHealthContractAndDoesNotRunDatabaseCheckInStandalone() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}", true));
        assertFalse(context.containsBean("databaseStartupCheck"));
    }

    @Test
    void wrapsSuccessAndPreservesCreatedStatus() throws Exception {
        sendBody("{\"name\":\"Book\",\"quantity\":2}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.data.name").value("Book"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "{\"name\":\" \",\"quantity\":1}|VALIDATION_FAILED",
        "{\"name\":\"123456789\",\"quantity\":1}|VALIDATION_FAILED",
        "{\"name\":\"Book\"}|VALIDATION_FAILED",
        "{\"name\":\"Book\",\"quantity\":0}|VALIDATION_FAILED",
        "{\"name\":\"Book\",\"quantity\":6}|VALIDATION_FAILED",
        "{\"name\":|INVALID_REQUEST_BODY"
    })
    void rejectsInvalidBodies(String body, String code) throws Exception {
        expectFailure(sendBody(body), 400, code, null);
    }

    @Test
    void rejectsMissingInvalidAndOutOfRangeQueryParameters() throws Exception {
        expectFailure(mvc.perform(get("/test-api/quantity")), 400, "INVALID_REQUEST_PARAMETER", null);
        expectFailure(mvc.perform(get("/test-api/quantity").param("value", "bad")),
                400, "INVALID_REQUEST_PARAMETER", "bad");
        expectFailure(mvc.perform(get("/test-api/quantity").param("value", "0")),
                400, "VALIDATION_FAILED", null);
    }

    @ParameterizedTest
    @CsvSource({
        "business, 404, PRODUCT_NOT_FOUND",
        "constraint, 409, DATA_CONFLICT",
        "unexpected, 500, INTERNAL_ERROR"
    })
    void mapsErrorsWithoutLeakingDetails(String kind, int httpStatus, String code) throws Exception {
        expectFailure(mvc.perform(get("/test-api/failure").param("kind", kind)),
                httpStatus, code, "private diagnostic");
    }

    @Test
    void preservesMethodNotAllowedStatusAndHeaders() throws Exception {
        expectFailure(mvc.perform(post("/api/health")), 405, "HTTP_405", null)
                .andExpect(header().exists("Allow"));
    }

    private ResultActions sendBody(String body) throws Exception {
        return mvc.perform(post("/test-api/input").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions expectFailure(ResultActions result, int httpStatus, String code, String privateText)
            throws Exception {
        return result.andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(response -> {
                    if (privateText != null) {
                        assertFalse(response.getResponse().getContentAsString().contains(privateText));
                    }
                });
    }

    @RestController
    static class TestController {
        @PostMapping("/test-api/input")
        ResponseEntity<ApiResponse<TestRequest>> create(@Valid @RequestBody TestRequest input) {
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(input));
        }

        @GetMapping("/test-api/quantity")
        ApiResponse<Integer> quantity(@RequestParam @Min(1) int value) {
            return ApiResponse.success(value);
        }

        @GetMapping("/test-api/failure")
        ApiResponse<Void> fail(@RequestParam String kind) {
            throw switch (kind) {
                case "business" -> new BusinessException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "商品不存在");
                case "constraint" -> new DataIntegrityViolationException("private diagnostic");
                default -> new IllegalStateException("private diagnostic");
            };
        }
    }

    record TestRequest(@NotBlank @Size(max = 8) String name, @NotNull @Min(1) @Max(5) Integer quantity) {
    }
}
