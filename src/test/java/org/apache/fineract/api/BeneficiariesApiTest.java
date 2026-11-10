package org.apache.fineract.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.api.BeneficiariesApi.BeneficiaryRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * PHEE-469: /api/v1/beneficiaries proxies to account-mapper. account-mapper here is a JDK HttpServer that keeps
 * beneficiaries per X-Registering-Institution-ID and answers the way the real one does: an unknown payee is a 200 with
 * empty modality fields, a stored modality loses its leading zero, and register/update answer 202 even for a
 * beneficiary they rejected.
 */
class BeneficiariesApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();
    private final List<String> institutionHeaders = new ArrayList<>();
    private final Map<String, Map<String, Object>> stored = new LinkedHashMap<>();
    private int failNextWith;
    private int failNextWriteWith;
    private boolean failNextWriteAfterSaving;
    private String lastCallbackUrl;
    private Map<String, Object> lastWriteBody;
    private BeneficiariesApi api;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::accountMapper);
        server.start();
        api = new BeneficiariesApi(new RestTemplate(), JSON, "http://127.0.0.1:" + server.getAddress().getPort(),
                "http://localhost/callback");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void listSendsTheTenantAsRegisteringInstitutionAndPassesPagingThrough() {
        ResponseEntity<String> response = api.list("greenbank", 2, 5);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(List.of("GET /beneficiaries?page=2&pageSize=5"), requests);
        assertEquals(List.of("greenbank"), institutionHeaders);
        assertTrue(response.getBody().contains("\"totalElements\""));
    }

    @Test
    void accountMapperErrorsAreReturnedAsErrors() {
        failNextWith = 500;

        ResponseEntity<String> response = api.list("greenbank", 0, 20);

        assertEquals(500, response.getStatusCode().value());
    }

    @Test
    void unreachableAccountMapperIsABadGateway() {
        server.stop(0);

        assertEquals(502, api.list("greenbank", 0, 20).getStatusCode().value());
    }

    @Test
    void unknownPayeeIsNotFound() {
        assertEquals(404, api.get("greenbank", "0495822412").getStatusCode().value());
    }

    @Test
    void anotherTenantsBeneficiaryIsNotVisible() {
        api.register("bluebank", beneficiary("00", "12"));

        assertEquals(200, api.get("bluebank", "0495822412").getStatusCode().value());
        assertEquals(404, api.get("greenbank", "0495822412").getStatusCode().value());
    }

    @Test
    void registerStoresUnderTheTenantAndReturnsTheRecord() throws Exception {
        ResponseEntity<String> response = api.register("greenbank", beneficiary("00", "12"));

        assertEquals(201, response.getStatusCode().value());
        assertEquals("12", JSON.readValue(response.getBody(), Map.class).get("financialAddress"));
        assertTrue(institutionHeaders.stream().allMatch("greenbank"::equals));
        assertTrue(stored.containsKey("greenbank/0495822412"));
        assertEquals("greenbank", lastWriteBody.get("sourceBBID"));
        assertEquals(12, ((String) lastWriteBody.get("requestID")).length());
        assertEquals("http://localhost/callback", lastCallbackUrl);
    }

    @Test
    void registeringAnExistingPayeeIsAConflictAndSendsNothing() {
        api.register("greenbank", beneficiary("00", "12"));
        requests.clear();

        ResponseEntity<String> response = api.register("greenbank", beneficiary("00", "99"));

        assertEquals(409, response.getStatusCode().value());
        assertEquals(List.of("GET /beneficiaries/0495822412"), requests);
    }

    @Test
    void aBeneficiaryAccountMapperRejectedIsUnprocessable() {
        // account-mapper answers 202 and only tells the callback that modality 07 is invalid
        ResponseEntity<String> response = api.register("greenbank", beneficiary("07", "12"));

        assertEquals(422, response.getStatusCode().value());
    }

    @Test
    void aFailedCallbackAfterSavingStillCountsAsRegistered() {
        // account-mapper saves the beneficiary, then answers 500 because the callback URL did not answer
        failNextWriteAfterSaving = true;

        assertEquals(201, api.register("greenbank", beneficiary("00", "12")).getStatusCode().value());
    }

    @Test
    void accountMapperValidationErrorsArePassedOn() {
        failNextWriteWith = 400;

        assertEquals(400, api.register("greenbank", beneficiary("00", "12")).getStatusCode().value());
    }

    @Test
    void updateOfAnUnknownPayeeIsNotFound() {
        assertEquals(404, api.update("greenbank", beneficiary("00", "12")).getStatusCode().value());
        assertTrue(requests.stream().noneMatch(r -> r.startsWith("PUT")));
    }

    @Test
    void updateChangesTheRecord() throws Exception {
        api.register("greenbank", beneficiary("00", "12"));

        ResponseEntity<String> response = api.update("greenbank", beneficiary("01", "34"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("34", JSON.readValue(response.getBody(), Map.class).get("financialAddress"));
    }

    @Test
    void missingPayeeIdentityIsABadRequest() {
        ResponseEntity<String> response = api.register("greenbank", new BeneficiaryRequest(" ", "00", "12", "bluebank"));

        assertEquals(400, response.getStatusCode().value());
        assertTrue(requests.isEmpty());
    }

    private static BeneficiaryRequest beneficiary(String modality, String address) {
        return new BeneficiaryRequest("0495822412", modality, address, "bluebank");
    }

    private void accountMapper(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        requests.add(method + " " + path + (query == null ? "" : "?" + query));
        List<String> institution = exchange.getRequestHeaders().get("X-Registering-Institution-ID");
        institutionHeaders.addAll(institution == null ? List.of() : institution);
        String tenant = institution == null ? null : institution.get(0);

        if (failNextWith != 0) {
            int status = failNextWith;
            failNextWith = 0;
            reply(exchange, status, Map.of("error", "failed"));
        } else if ("GET".equals(method) && "/beneficiaries".equals(path)) {
            List<Map<String, Object>> content = stored.entrySet().stream().filter(e -> e.getKey().startsWith(tenant + "/"))
                    .map(Map.Entry::getValue).toList();
            reply(exchange, 200, Map.of("content", content, "totalElements", content.size(), "totalPages", 1));
        } else if ("GET".equals(method) && path.startsWith("/beneficiaries/")) {
            String payee = path.substring("/beneficiaries/".length());
            Map<String, Object> record = stored.get(tenant + "/" + payee);
            if (record == null) {
                record = new LinkedHashMap<>();
                record.put("registeringInstitutionId", tenant);
                record.put("payeeIdentity", payee);
                record.put("paymentModality", null);
                record.put("financialAddress", null);
                record.put("bankingInstitutionCode", null);
            }
            reply(exchange, 200, record);
        } else if ("/beneficiary".equals(path)) {
            if (failNextWriteWith != 0) {
                reply(exchange, failNextWriteWith, Map.of("errorCode", "validation"));
                failNextWriteWith = 0;
                return;
            }
            lastCallbackUrl = exchange.getRequestHeaders().getFirst("X-CallbackURL");
            Map<String, Object> body = JSON.readValue(exchange.getRequestBody(), Map.class);
            lastWriteBody = body;
            Map<String, Object> entry = ((List<Map<String, Object>>) body.get("beneficiaries")).get(0);
            String key = tenant + "/" + entry.get("payeeIdentity");
            boolean valid = List.of("00", "01", "02", "03", "04").contains(entry.get("paymentModality"));
            boolean accepted = "POST".equals(method) ? !stored.containsKey(key) : stored.containsKey(key);
            if (valid && accepted) {
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("registeringInstitutionId", tenant);
                record.putAll(entry);
                // the modality column is a BIGINT: 00 is read back as 0
                record.put("paymentModality", String.valueOf(Integer.parseInt((String) entry.get("paymentModality"))));
                stored.put(key, record);
            }
            if (failNextWriteAfterSaving) {
                failNextWriteAfterSaving = false;
                reply(exchange, 500, Map.of("responseCode", "01"));
            } else {
                reply(exchange, 202, Map.of("responseCode", "00"));
            }
        } else {
            reply(exchange, 404, Map.of());
        }
    }

    private static void reply(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
