package org.apache.fineract.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Beneficiary (payee to account) mappings of the tenant, held by paymenthub-ee-account-mapper. The operations web
 * console reaches account-mapper only through here: account-mapper has no authentication and trusts whatever
 * X-Registering-Institution-ID it is sent, so that header is always the request's tenant, never a value from the
 * browser.
 *
 * Register and update answer 202 whatever happened to the beneficiary. account-mapper reports a rejected one
 * (already registered, invalid identity, modality, institution code or address) only to the X-CallbackURL, so the
 * outcome is confirmed here by reading the record back.
 */
@RestController
@SecurityRequirement(name = "auth")
@Tag(name = "Beneficiaries")
@RequestMapping("/api/v1/beneficiaries")
public class BeneficiariesApi {

    private static final Logger logger = LoggerFactory.getLogger(BeneficiariesApi.class);
    private static final String REGISTERING_INSTITUTION_HEADER = "X-Registering-Institution-ID";
    private static final String REQUEST_ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    // account-mapper rejects a requestID longer than this
    private static final int REQUEST_ID_LENGTH = 12;
    private static final List<String> MODALITY_FIELDS = List.of("paymentModality", "financialAddress", "bankingInstitutionCode");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String accountMapperUrl;
    private final String callbackUrl;
    private final SecureRandom random = new SecureRandom();

    public BeneficiariesApi(RestTemplate restTemplate, ObjectMapper objectMapper,
                            @Value("${account-mapper.url}") String accountMapperUrl,
                            @Value("${account-mapper.callback-url}") String callbackUrl) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.accountMapperUrl = accountMapperUrl;
        this.callbackUrl = callbackUrl;
    }

    public record BeneficiaryRequest(String payeeIdentity, String paymentModality, String financialAddress,
                                     String bankingInstitutionCode) {
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> list(@RequestHeader("Platform-TenantId") String tenantId,
                                       @RequestParam(value = "page", defaultValue = "0") Integer page,
                                       @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize) {
        try {
            return ok(restTemplate.exchange(accountMapperUrl + "/beneficiaries?page={page}&pageSize={pageSize}", HttpMethod.GET,
                    new HttpEntity<>(headers(tenantId)), String.class, page, pageSize).getBody());
        } catch (HttpStatusCodeException e) {
            return passOn(e);
        } catch (ResourceAccessException e) {
            return unreachable(e);
        }
    }

    @GetMapping(path = "/{payeeIdentity}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> get(@RequestHeader("Platform-TenantId") String tenantId,
                                      @PathVariable("payeeIdentity") String payeeIdentity) {
        try {
            return find(tenantId, payeeIdentity).map(BeneficiariesApi::ok)
                    .orElseGet(() -> error(HttpStatus.NOT_FOUND, "No beneficiary " + payeeIdentity));
        } catch (HttpStatusCodeException e) {
            return passOn(e);
        } catch (ResourceAccessException e) {
            return unreachable(e);
        }
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> register(@RequestHeader("Platform-TenantId") String tenantId,
                                           @RequestBody BeneficiaryRequest beneficiary) {
        return write(tenantId, beneficiary, HttpMethod.POST);
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> update(@RequestHeader("Platform-TenantId") String tenantId,
                                         @RequestBody BeneficiaryRequest beneficiary) {
        return write(tenantId, beneficiary, HttpMethod.PUT);
    }

    private ResponseEntity<String> write(String tenantId, BeneficiaryRequest beneficiary, HttpMethod method) {
        if (beneficiary == null || beneficiary.payeeIdentity() == null || beneficiary.payeeIdentity().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "payeeIdentity is required");
        }
        String payeeIdentity = beneficiary.payeeIdentity();
        try {
            boolean exists = find(tenantId, payeeIdentity).isPresent();
            if (method == HttpMethod.POST && exists) {
                return error(HttpStatus.CONFLICT, "Beneficiary " + payeeIdentity + " is already registered");
            }
            if (method == HttpMethod.PUT && !exists) {
                return error(HttpStatus.NOT_FOUND, "No beneficiary " + payeeIdentity);
            }

            try {
                restTemplate.exchange(accountMapperUrl + "/beneficiary", method,
                        new HttpEntity<>(objectMapper.writeValueAsString(requestBody(tenantId, beneficiary)), writeHeaders(tenantId)),
                        String.class);
            } catch (HttpStatusCodeException e) {
                // a 400 is account-mapper's own validation of the request. A 500 also comes back when only the callback
                // failed, after the beneficiary was saved, so the read below decides.
                if (e.getStatusCode().is4xxClientError()) {
                    return passOn(e);
                }
                logger.debug("account-mapper answered {} to {} /beneficiary, checking the record", e.getStatusCode(), method);
            }

            Optional<String> stored = find(tenantId, payeeIdentity);
            if (stored.isPresent() && matches(stored.get(), beneficiary)) {
                return ResponseEntity.status(method == HttpMethod.POST ? HttpStatus.CREATED : HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON).body(stored.get());
            }
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "account-mapper did not store beneficiary " + payeeIdentity
                    + ": check the payee identity (at most 20 characters), payment modality (00-04), banking institution code"
                    + " (at most 11 characters, required for 00, 01 and 03) and financial address (at most 30 characters)");
        } catch (HttpStatusCodeException e) {
            return passOn(e);
        } catch (ResourceAccessException e) {
            return unreachable(e);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * account-mapper answers an unknown payee with 200 and empty modality fields, so a record counts as found only
     * with at least one of them set.
     */
    private Optional<String> find(String tenantId, String payeeIdentity) {
        String body = restTemplate.exchange(accountMapperUrl + "/beneficiaries/{payeeIdentity}", HttpMethod.GET,
                new HttpEntity<>(headers(tenantId)), String.class, payeeIdentity).getBody();
        Map<String, Object> record = parse(body);
        return MODALITY_FIELDS.stream().anyMatch(field -> record.get(field) != null) ? Optional.of(body) : Optional.empty();
    }

    private boolean matches(String stored, BeneficiaryRequest sent) {
        Map<String, Object> record = parse(stored);
        return Objects.equals(modalityNumber(record.get("paymentModality")), modalityNumber(sent.paymentModality()))
                && Objects.equals(record.get("financialAddress"), sent.financialAddress())
                && Objects.equals(record.get("bankingInstitutionCode"), sent.bankingInstitutionCode());
    }

    /** account-mapper keeps the modality in a BIGINT column, so 00 is read back as 0. */
    private static String modalityNumber(Object modality) {
        return modality == null ? null : modality.toString().replaceFirst("^0+(?=.)", "");
    }

    private Map<String, Object> requestBody(String tenantId, BeneficiaryRequest beneficiary) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("payeeIdentity", beneficiary.payeeIdentity());
        entry.put("paymentModality", beneficiary.paymentModality());
        entry.put("financialAddress", beneficiary.financialAddress());
        entry.put("bankingInstitutionCode", beneficiary.bankingInstitutionCode());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestID", requestId());
        body.put("sourceBBID", tenantId);
        body.put("beneficiaries", List.of(entry));
        return body;
    }

    private String requestId() {
        StringBuilder id = new StringBuilder(REQUEST_ID_LENGTH);
        for (int i = 0; i < REQUEST_ID_LENGTH; i++) {
            id.append(REQUEST_ID_CHARS.charAt(random.nextInt(REQUEST_ID_CHARS.length())));
        }
        return id.toString();
    }

    private HttpHeaders headers(String tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(REGISTERING_INSTITUTION_HEADER, tenantId);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    private HttpHeaders writeHeaders(String tenantId) {
        HttpHeaders headers = headers(tenantId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-CallbackURL", callbackUrl);
        return headers;
    }

    private Map<String, Object> parse(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(body, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("account-mapper sent a body that is not JSON: " + body, e);
        }
    }

    private static ResponseEntity<String> ok(String body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static ResponseEntity<String> passOn(HttpStatusCodeException e) {
        return ResponseEntity.status(e.getStatusCode()).contentType(MediaType.APPLICATION_JSON).body(e.getResponseBodyAsString());
    }

    private ResponseEntity<String> unreachable(ResourceAccessException e) {
        logger.error("account-mapper at {} is unreachable: {}", accountMapperUrl, e.getMessage());
        return error(HttpStatus.BAD_GATEWAY, "account-mapper is unreachable");
    }

    private ResponseEntity<String> error(HttpStatus status, String message) {
        try {
            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(Map.of("message", message)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
