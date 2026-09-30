package com.viris.PulseGuard.ai;

import com.viris.PulseGuard.ai.dto.AiAccessDto;
import com.viris.PulseGuard.ai.dto.AiQuotaResponse;
import com.viris.PulseGuard.common.exception.AiRuleException;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Ask AI's consent and daily limit: whether the user's monitoring data may be sent to the AI
 * provider, which monitors, and how many questions are left today. Asking itself is the chat
 * (ai/chat/ChatService), which reads both.
 */
@Service
public class AskAiService {

    private final AiAccessRepository accessRepository;
    private final MonitorRepository monitorRepository;
    private final AiQuotaPolicy quotaPolicy;

    public AskAiService(AiAccessRepository accessRepository, MonitorRepository monitorRepository,
                        AiQuotaPolicy quotaPolicy) {
        this.accessRepository = accessRepository;
        this.monitorRepository = monitorRepository;
        this.quotaPolicy = quotaPolicy;
    }

    @Transactional(readOnly = true)
    public AiAccessDto access(Long userId) {
        return toDto(accessRepository.findById(userId).orElseGet(() -> new AiAccess(userId)));
    }

    /**
     * Saves the user's consent. Monitor ids are kept only if the user owns them, so the list
     * can never name another tenant's monitor.
     */
    @Transactional
    public AiAccessDto saveAccess(Long userId, AiAccessDto request) {
        Set<Long> owned = monitorRepository.findAllByUserId(userId).stream()
                .map(Monitor::getId).collect(Collectors.toSet());
        Set<Long> chosen = request.monitorIds().stream().filter(owned::contains).collect(Collectors.toSet());
        if (request.enabled() && !request.allMonitors() && chosen.isEmpty()) {
            throw AiRuleException.noMonitorSelected();
        }
        AiAccess access = accessRepository.findById(userId).orElseGet(() -> new AiAccess(userId));
        access.setEnabled(request.enabled());
        access.setAllMonitors(request.allMonitors());
        access.getMonitorIds().clear();
        access.getMonitorIds().addAll(chosen);
        access.setUpdatedAt(Instant.now());
        return toDto(accessRepository.save(access));
    }

    public AiQuotaResponse quota(Long userId) {
        return quotaPolicy.status(userId);
    }

    private static AiAccessDto toDto(AiAccess access) {
        return new AiAccessDto(access.isEnabled(), access.isAllMonitors(),
                access.getMonitorIds().stream().sorted().toList());
    }
}
