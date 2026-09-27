package com.viris.PulseGuard.statuspage;

import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.common.exception.InvalidStatusPageException;
import com.viris.PulseGuard.common.exception.MonitorNotFoundException;
import com.viris.PulseGuard.common.exception.SlugTakenException;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
import com.viris.PulseGuard.statuspage.dto.StatusPageMonitorDto;
import com.viris.PulseGuard.statuspage.dto.StatusPageRequest;
import com.viris.PulseGuard.statuspage.dto.StatusPageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The owner's side of the status page: one page per account, created or replaced as a whole.
 * Every monitor on it must belong to the caller; someone else's id is a 404, as everywhere.
 */
@Service
public class StatusPageService {

    private static final Logger log = LoggerFactory.getLogger(StatusPageService.class);

    /** 3-40 characters, lowercase letters, digits and inner dashes. */
    static final Pattern SLUG = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{1,38}[a-z0-9])$");
    /** Addresses that would read as ours rather than the customer's. */
    static final Set<String> RESERVED = Set.of("admin", "api", "app", "login", "signup", "status", "www", "pulseguard");
    static final int MAX_DISPLAY_NAME = 100;

    private final StatusPageRepository statusPageRepository;
    private final MonitorRepository monitorRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher events;

    public StatusPageService(StatusPageRepository statusPageRepository,
                             MonitorRepository monitorRepository,
                             UserRepository userRepository,
                             ApplicationEventPublisher events) {
        this.statusPageRepository = statusPageRepository;
        this.monitorRepository = monitorRepository;
        this.userRepository = userRepository;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public Optional<StatusPageResponse> getStatusPage(Long userId) {
        return statusPageRepository.findByUserId(userId).map(StatusPageResponse::from);
    }

    @Transactional
    public StatusPageResponse saveStatusPage(Long userId, StatusPageRequest request) {
        String slug = request.slug().trim().toLowerCase(Locale.ROOT);
        List<StatusPageEntry> entries = validate(slug, request.monitors());
        requireOwnedMonitors(userId, entries);
        if (statusPageRepository.existsBySlugAndUserIdNot(slug, userId)) {
            throw new SlugTakenException();
        }

        StatusPage page = statusPageRepository.findByUserId(userId).orElseGet(() -> {
            StatusPage created = new StatusPage();
            created.setUser(userRepository.getReferenceById(userId));
            return created;
        });
        String previousSlug = page.getSlug();
        page.setSlug(slug);
        page.setTitle(request.title().trim());
        page.setDescription(request.description() == null ? "" : request.description().trim());
        page.setPublished(request.published());
        page.setEntries(entries); // a new list: see StatusPage#entries

        try {
            // saveAndFlush: a slug claimed between the check above and now surfaces here, as a 409.
            statusPageRepository.saveAndFlush(page);
        } catch (DataIntegrityViolationException e) {
            throw new SlugTakenException();
        }

        Set<String> changed = new HashSet<>();
        changed.add(slug);
        if (previousSlug != null) {
            changed.add(previousSlug);
        }
        events.publishEvent(new StatusPageChangedEvent(changed));
        log.info("Saved status page slug={} published={} for userId={}", slug, page.isPublished(), userId);
        return StatusPageResponse.from(page);
    }

    /** Checks that need the normalised input, all at once so the form can show every problem. */
    private static List<StatusPageEntry> validate(String slug, List<StatusPageMonitorDto> monitors) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (!SLUG.matcher(slug).matches()) {
            errors.put("slug", "Use 3–40 lowercase letters, numbers or dashes");
        } else if (RESERVED.contains(slug)) {
            errors.put("slug", "That address is reserved");
        }

        List<StatusPageEntry> entries = new ArrayList<>(monitors.size());
        Set<Long> seen = new HashSet<>();
        for (StatusPageMonitorDto m : monitors) {
            String name = m == null || m.displayName() == null ? "" : m.displayName().trim();
            if (m == null || m.monitorId() == null || name.isEmpty()) {
                errors.put("monitors", "Every monitor needs a display name");
                break;
            }
            if (name.length() > MAX_DISPLAY_NAME) {
                errors.put("monitors", "Display names must be " + MAX_DISPLAY_NAME + " characters or fewer");
                break;
            }
            if (!seen.add(m.monitorId())) {
                errors.put("monitors", "Each monitor can appear only once");
                break;
            }
            entries.add(new StatusPageEntry(m.monitorId(), name, entries.size()));
        }

        if (!errors.isEmpty()) {
            throw new InvalidStatusPageException(errors);
        }
        return entries;
    }

    private void requireOwnedMonitors(Long userId, List<StatusPageEntry> entries) {
        Set<Long> owned = monitorRepository.findAllByUserId(userId).stream()
                .map(Monitor::getId)
                .collect(Collectors.toSet());
        for (StatusPageEntry entry : entries) {
            if (!owned.contains(entry.getMonitorId())) {
                throw new MonitorNotFoundException(entry.getMonitorId());
            }
        }
    }
}
