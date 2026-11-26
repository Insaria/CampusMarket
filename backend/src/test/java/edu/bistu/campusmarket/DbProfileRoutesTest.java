package edu.bistu.campusmarket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@SpringBootTest(properties = {
        "app.orders.auto-confirm-delay=PT1H",
        "app.orders.settlement-delay=PT1H",
        "app.orders.refund-points-delay=PT1H"
})
@AutoConfigureMockMvc
@ActiveProfiles("db")
class DbProfileRoutesTest {
    @MockitoBean
    private DataSource dataSource;
    @MockitoBean
    private DatabaseStartupCheck databaseStartupCheck;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping routes;
    @Autowired
    private MockMvc mvc;

    @Test
    void fullDbProfileRegistersRoutesAndProtectsReviewImages() throws Exception {
        var paths = routes.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPatternValues().stream()).toList();
        assertThat(paths).contains("/api/admin/users/{id}/wallet/recharges", "/api/admin/products/{id}/images/{position}",
                "/api/notifications/{id}", "/api/orders", "/api/checkout-batches/{batchId}", "/api/categories");
        mvc.perform(get("/api/health")).andExpect(status().isOk());
        mvc.perform(get("/api/admin/products/4/images/0")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/checkout-batches/4")).andExpect(status().isUnauthorized());
    }
}
