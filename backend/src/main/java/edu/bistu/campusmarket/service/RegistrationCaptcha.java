package edu.bistu.campusmarket.service;

import edu.bistu.campusmarket.exception.BusinessException;
import jakarta.servlet.http.HttpSession;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@Profile("db")
public class RegistrationCaptcha {
    private static final String ATTRIBUTE = RegistrationCaptcha.class.getName();
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final Duration LIFETIME = Duration.ofMinutes(2);

    private final Clock clock;
    private final SecureRandom random;

    public RegistrationCaptcha() {
        this(Clock.systemUTC(), new SecureRandom());
    }

    RegistrationCaptcha(Clock clock, SecureRandom random) {
        this.clock = clock;
        this.random = random;
    }

    public byte[] issue(HttpSession session) throws IOException {
        synchronized (session) {
            StringBuilder answer = new StringBuilder(6);
            for (int index = 0; index < 6; index++) {
                answer.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            byte[] image = render(answer.toString());
            session.setAttribute(ATTRIBUTE, new Challenge(answer.toString(), clock.instant().plus(LIFETIME)));
            return image;
        }
    }

    public void consume(HttpSession session, String answer) {
        if (session != null) {
            synchronized (session) {
                Object challenge = session.getAttribute(ATTRIBUTE);
                session.removeAttribute(ATTRIBUTE);
                if (challenge instanceof Challenge issued && clock.instant().isBefore(issued.expiresAt())
                        && issued.answer().equals(answer.toUpperCase(Locale.ROOT))) {
                    return;
                }
            }
        }
        throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_CAPTCHA", "验证码无效，请重新获取");
    }

    private byte[] render(String answer) throws IOException {
        BufferedImage image = new BufferedImage(180, 56, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(new Color(180, 195, 210));
            for (int index = 0; index < 12; index++) {
                graphics.drawLine(random.nextInt(180), random.nextInt(56), random.nextInt(180), random.nextInt(56));
            }
            graphics.setFont(new Font(Font.MONOSPACED, Font.BOLD, 30));
            graphics.setColor(new Color(25, 48, 85));
            for (int index = 0; index < answer.length(); index++) {
                graphics.drawString(answer.substring(index, index + 1), 15 + index * 25, 34 + random.nextInt(12));
            }
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private record Challenge(String answer, Instant expiresAt) {
        @Override
        public String toString() {
            return "Challenge[redacted]";
        }
    }
}
