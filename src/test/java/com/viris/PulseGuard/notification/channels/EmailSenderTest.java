package com.viris.PulseGuard.notification.channels;

import com.viris.PulseGuard.enumeration.ChannelType;
import com.viris.PulseGuard.notification.dto.AlertMessage;
import com.viris.PulseGuard.notification.dto.NotificationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EmailSenderTest {

    @Mock
    private JavaMailSender mailSender;

    private EmailSender sender;

    @BeforeEach
    void setUp() {
        sender = new EmailSender(mailSender, new NotificationProperties("PulseGuard <alerts@test.local>"));
    }

    @Test
    void handlesEmailChannels() {
        assertThat(sender.type()).isEqualTo(ChannelType.EMAIL);
    }

    @Test
    void sendsPlainTextMailFromConfiguredAddress() {
        sender.send("owner@example.com", new AlertMessage("Payments API is DOWN", "details"));

        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(mail.capture());
        assertThat(mail.getValue().getFrom()).isEqualTo("PulseGuard <alerts@test.local>");
        assertThat(mail.getValue().getTo()).containsExactly("owner@example.com");
        assertThat(mail.getValue().getSubject()).isEqualTo("Payments API is DOWN");
        assertThat(mail.getValue().getText()).isEqualTo("details");
    }

    @Test
    void propagatesSmtpFailuresToTheCaller() {
        // send(...) returns void, so doThrow().when(), not when().thenThrow().
        doThrow(new MailSendException("SMTP down")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> sender.send("owner@example.com", new AlertMessage("s", "b")))
                .isInstanceOf(MailSendException.class);
    }
}
