package kelpserver;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Sends email (only for parents: approve links and sign-in links) with a small SMTP client: connect, switch to an
 * encrypted connection (STARTTLS), sign in, send. With no mail server set up, it prints the email instead, which is
 * how tests and local testing see what would be sent.
 */
public class Mail {
    private final Config config;
    /** What was sent, newest last, when there's no mail server (for tests). */
    public final List<String[]> outbox = new ArrayList<>();

    public Mail(Config config) {
        this.config = config;
    }

    public synchronized void send(String to, String subject, String text) throws IOException {
        if (!to.matches("[^\\s@<>\"]{1,64}@[^\\s@<>\"]{1,190}\\.[A-Za-z]{2,}")) throw new IOException("That email address doesn't look right.");
        if (config.mailHost.isEmpty()) {
            outbox.add(new String[] {to, subject, text});
            System.out.println("[mail to " + to + "] " + subject + "\n" + text);
            return;
        }
        try (Socket plain = new Socket(config.mailHost, config.mailPort)) {
            plain.setSoTimeout(20_000);
            Conversation c = new Conversation(plain);
            c.expect(220);
            c.say("EHLO kelplauncher.org", 250);
            c.say("STARTTLS", 220);
            SSLSocket secure = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(plain, config.mailHost, config.mailPort, true);
            secure.startHandshake();
            c = new Conversation(secure);
            c.say("EHLO kelplauncher.org", 250);
            c.say("AUTH LOGIN", 334);
            c.say(Base64.getEncoder().encodeToString(config.mailUser.getBytes(StandardCharsets.UTF_8)), 334);
            c.say(Base64.getEncoder().encodeToString(config.mailPassword.getBytes(StandardCharsets.UTF_8)), 235);
            String from = config.mailFrom.replaceAll(".*<|>.*", "");
            c.say("MAIL FROM:<" + from + ">", 250);
            c.say("RCPT TO:<" + to + ">", 250);
            c.say("DATA", 354);
            String body = "From: " + config.mailFrom + "\r\nTo: " + to + "\r\nSubject: " + subject.replaceAll("[\r\n]", " ")
                    + "\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n"
                    + text.replace("\r\n", "\n").replace("\n.", "\n..").replace("\n", "\r\n") + "\r\n.";
            c.say(body, 250);
            c.say("QUIT", 221);
        }
    }

    private static final class Conversation {
        private final BufferedReader in;
        private final OutputStream out;

        Conversation(Socket socket) throws IOException {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = socket.getOutputStream();
        }

        void say(String line, int expected) throws IOException {
            out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            expect(expected);
        }

        void expect(int code) throws IOException {
            String line;
            do {
                line = in.readLine();
                if (line == null) throw new IOException("the mail server hung up");
            } while (line.length() > 3 && line.charAt(3) == '-'); // multi-line answers
            if (!line.startsWith(String.valueOf(code))) throw new IOException("the mail server said: " + line);
        }
    }
}
