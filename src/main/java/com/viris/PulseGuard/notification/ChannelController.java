package com.viris.PulseGuard.notification;

import com.viris.PulseGuard.auth.UserPrincipal;
import com.viris.PulseGuard.auth.dto.TokenRequest;
import com.viris.PulseGuard.notification.dto.ChannelRequest;
import com.viris.PulseGuard.notification.dto.ChannelResponse;
import com.viris.PulseGuard.notification.dto.ChannelUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

/**
 * Where alerts go. The caller's id always comes from the token, so one tenant cannot name
 * another's channel; someone else's channel is a 404, not a 403.
 */
@RestController
@RequestMapping("/api/channels")
@RequiredArgsConstructor
public class ChannelController {

    private final ChannelService channelService;
    private final ChannelConfirmationService confirmationService;

    @GetMapping
    public List<ChannelResponse> list(@AuthenticationPrincipal UserPrincipal principal) {
        return channelService.listChannels(principal.getUserId());
    }

    @PostMapping
    public ResponseEntity<ChannelResponse> create(@AuthenticationPrincipal UserPrincipal principal,
                                                  @Valid @RequestBody ChannelRequest request,
                                                  UriComponentsBuilder uriBuilder) {
        ChannelResponse created = channelService.createChannel(principal.getUserId(), request);
        return ResponseEntity
                .created(uriBuilder.path("/api/channels/{id}").build(created.id()))
                .body(created);
    }

    @PatchMapping("/{id}")
    public ChannelResponse update(@AuthenticationPrincipal UserPrincipal principal,
                                  @PathVariable Long id,
                                  @Valid @RequestBody ChannelUpdateRequest request) {
        return channelService.setEnabled(principal.getUserId(), id, request.enabled());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal UserPrincipal principal,
                       @PathVariable Long id) {
        channelService.deleteChannel(principal.getUserId(), id);
    }

    @PostMapping("/{id}/resend-confirmation")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resendConfirmation(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        channelService.resendConfirmation(principal.getUserId(), id);
    }

    /** From the link emailed to the channel's address; open to anyone holding it (SecurityConfig). */
    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@Valid @RequestBody TokenRequest request) {
        confirmationService.confirm(request.token());
    }

    @PostMapping("/{id}/test")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void test(@AuthenticationPrincipal UserPrincipal principal,
                     @PathVariable Long id) {
        channelService.sendTestAlert(principal.getUserId(), id);
    }
}
