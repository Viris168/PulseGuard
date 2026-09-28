package com.viris.PulseGuard.apikey;

import com.viris.PulseGuard.apikey.dto.ApiKeyRequest;
import com.viris.PulseGuard.apikey.dto.ApiKeyResponse;
import com.viris.PulseGuard.apikey.dto.CreatedApiKeyResponse;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.common.exception.ApiKeyNotFoundException;
import com.viris.PulseGuard.common.exception.ApiKeyRuleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Personal API keys: create, list, revoke, and resolve an incoming key to its owner. Every
 * owner-side query is scoped by the caller's id; another account's key is a 404.
 */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    static final int MAX_KEYS = 10;
    /** "Last used" is shown as "5 minutes ago"; a minute's precision costs one write per minute at most. */
    static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final ApiKeyRepository apiKeyRepository;
    private final UserRepository userRepository;

    public ApiKeyService(ApiKeyRepository apiKeyRepository, UserRepository userRepository) {
        this.apiKeyRepository = apiKeyRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<ApiKeyResponse> listKeys(Long userId) {
        return apiKeyRepository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(ApiKeyResponse::from)
                .toList();
    }

    @Transactional
    public CreatedApiKeyResponse createKey(Long userId, ApiKeyRequest request) {
        String name = request.name().trim();
        if (apiKeyRepository.countByUserId(userId) >= MAX_KEYS) {
            throw ApiKeyRuleException.tooMany(MAX_KEYS);
        }
        if (apiKeyRepository.existsByUserIdAndNameIgnoreCase(userId, name)) {
            throw ApiKeyRuleException.duplicateName();
        }

        String secret = ApiKeySecrets.generate();
        ApiKey key = new ApiKey();
        key.setUser(userRepository.getReferenceById(userId));
        key.setName(name);
        key.setPrefix(ApiKeySecrets.displayPrefix(secret));
        key.setKeyHash(ApiKeySecrets.hash(secret));
        try {
            // saveAndFlush: a same-named key created concurrently surfaces here, as the 409 above.
            apiKeyRepository.saveAndFlush(key);
        } catch (DataIntegrityViolationException e) {
            throw ApiKeyRuleException.duplicateName();
        }

        log.info("Created apiKeyId={} for userId={}", key.getId(), userId);
        return new CreatedApiKeyResponse(ApiKeyResponse.from(key), secret);
    }

    /** Deleted outright: the next request with it finds no row and is a 401. */
    @Transactional
    public void revokeKey(Long userId, Long keyId) {
        ApiKey key = apiKeyRepository.findByIdAndUserId(keyId, userId)
                .orElseThrow(() -> new ApiKeyNotFoundException(keyId));
        apiKeyRepository.delete(key);
        log.info("Revoked apiKeyId={} for userId={}", keyId, userId);
    }

    /**
     * The owner of {@code secret}, or empty. Looked up by hash through the unique index, so
     * the key is never compared character by character and its timing reveals nothing.
     */
    @Transactional
    public Optional<Long> authenticate(String secret) {
        return apiKeyRepository.findByKeyHash(ApiKeySecrets.hash(secret)).map(key -> {
            Instant now = Instant.now();
            apiKeyRepository.touch(key.getId(), now, now.minus(TOUCH_INTERVAL));
            return key.getUser().getId();
        });
    }
}
