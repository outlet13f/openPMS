package com.openpms.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Plain-text transactional mail through the company SMTP relay (architecture.md §1 이메일; Mailpit in development).
 * A failed send is logged and never fails the user's request: the action itself already succeeded.
 */
@Component
public class Mailer {
    private static final Logger log = LoggerFactory.getLogger(Mailer.class);
    private final JavaMailSender sender;
    private final String from;
    private final String baseUrl;

    public Mailer(JavaMailSender sender, @Value("${openpms.mail-from}") String from, @Value("${openpms.base-url}") String baseUrl) {
        this.sender = sender;
        this.from = from;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public String link(String path) {
        return baseUrl + path;
    }

    public void send(String to, String subject, String body) {
        try {
            var m = new SimpleMailMessage();
            m.setFrom(from);
            m.setTo(to);
            m.setSubject(subject);
            m.setText(body);
            sender.send(m);
        } catch (RuntimeException e) {
            // never log the body: it may carry a one-time link
            log.warn("could not send mail \"{}\": {}", subject, e.getMessage());
        }
    }
}
