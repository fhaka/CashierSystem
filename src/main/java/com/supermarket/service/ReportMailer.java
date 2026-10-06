package com.supermarket.service;

import com.supermarket.dto.PeriodReport;
import com.supermarket.exception.ValidationException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Sends the day's Z report by email (text in the message, PDF attached). Off unless pos.report.email.to is set
 * and a mail server is configured (spring.mail.host and friends). Sent automatically at pos.report.email.cron.
 */
@Service
public class ReportMailer {

    private static final Logger LOG = LoggerFactory.getLogger(ReportMailer.class);

    private final ObjectProvider<JavaMailSender> mailSender;
    private final ReportService reportService;
    private final ExportService exportService;
    private final MessageSource messageSource;
    private final List<String> recipients;
    private final String from;

    public ReportMailer(
            ObjectProvider<JavaMailSender> mailSender,
            ReportService reportService,
            ExportService exportService,
            MessageSource messageSource,
            @Value("${pos.report.email.to:}") String recipients,
            @Value("${pos.report.email.from:${spring.mail.username:}}") String from
    ) {
        this.mailSender = mailSender;
        this.reportService = reportService;
        this.exportService = exportService;
        this.messageSource = messageSource;
        this.recipients = Arrays.stream(recipients.split(",")).map(String::trim).filter(r -> !r.isEmpty()).toList();
        this.from = from;
    }

    public boolean isConfigured() {
        return !recipients.isEmpty() && mailSender.getIfAvailable() != null;
    }

    /** Sends the Z report of a day now. Returns the addresses it went to. */
    public List<String> sendDailyReport(LocalDate day) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (recipients.isEmpty() || sender == null) {
            throw new ValidationException("email.notConfigured");
        }
        PeriodReport report = reportService.dailyReport(day);
        Locale locale = LocaleContextHolder.getLocale();
        String subject = messageSource.getMessage("email.zSubject", new Object[] {day.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))},
                "Z " + day, locale);
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(recipients.toArray(String[]::new));
            if (from != null && !from.isBlank()) {
                helper.setFrom(from);
            }
            helper.setSubject(subject);
            helper.setText(report.printableText());
            helper.addAttachment("raporti-z-" + day + ".pdf", new ByteArrayResource(exportService.textPdf(report.printableText())),
                    "application/pdf");
            sender.send(message);
            return recipients;
        } catch (MessagingException exception) {
            throw new IllegalStateException("Could not build the report email", exception);
        }
    }

    @Scheduled(cron = "${pos.report.email.cron:0 30 23 * * *}", zone = "Europe/Tirane")
    public void sendScheduled() {
        if (!isConfigured()) {
            return;
        }
        try {
            sendDailyReport(LocalDate.now());
        } catch (RuntimeException exception) {
            LOG.warn("Daily report email failed: {}", exception.getMessage());
        }
    }
}
