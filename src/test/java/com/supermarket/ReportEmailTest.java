package com.supermarket;

import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = "pos.report.email.to=pronari@example.com, kontabilisti@example.com")
class ReportEmailTest extends IntegrationTest {

    @MockBean
    private JavaMailSender mailSender;

    @Test
    void dailyReportIsEmailedWithThePdf() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        String admin = registerSuperAdmin();

        getJson("/reports/email-settings", admin).andExpect(jsonPath("$.data.configured").value(true));
        postJson("/reports/daily/email?date=2026-10-01", admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sentTo.length()").value(2));

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(sent.capture());
        MimeMessage message = sent.getValue();
        assertThat(message.getSubject()).isEqualTo("Raporti Z - 01.10.2026");
        assertThat(message.getAllRecipients()).hasSize(2);
        Multipart body = (Multipart) message.getContent();
        boolean hasPdf = false;
        for (int i = 0; i < body.getCount(); i++) {
            hasPdf |= "raporti-z-2026-10-01.pdf".equals(body.getBodyPart(i).getFileName());
        }
        assertThat(hasPdf).isTrue();
    }
}
