package org.opennms.imap.alarms;

import java.io.FileReader;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.mail.Flags;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import javax.mail.search.MessageIDTerm;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.MediaType;

import org.glassfish.jersey.SslConfigurator;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature;
import org.glassfish.jersey.jackson.internal.jackson.jaxrs.json.JacksonJaxbJsonProvider;
import org.glassfish.jersey.jackson.internal.jackson.jaxrs.json.JacksonJsonProvider;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.ParserProperties;
import org.opennms.netmgt.model.OnmsAlarm;
import org.opennms.netmgt.model.OnmsAlarmCollection;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.google.common.html.HtmlEscapers;
import com.icegreen.greenmail.imap.AuthorizationException;
import com.icegreen.greenmail.store.FolderException;
import com.icegreen.greenmail.store.MailFolder;
import com.icegreen.greenmail.store.StoredMessage;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;

/**
 * OpenNMS Imap Alarms
 */
public class ImapAlarms {
    @Option(required = false, name = "--url", usage = "URL of your OpenNMS installation.")
    private String url = "http://localhost:8980/opennms";

    @Option(required = false, name = "--username", usage = "Username for accessing OpenNMS ReST endpoints.")
    private String username = null;

    @Option(required = false, name = "--password", usage = "Password for accessing OpenNMS ReST endpoints.")
    private String password = null;

    @Option(required = false, name = "--imap-port", usage = "Port for IMAP server.")
    private int imapPort = 1993;

    @Option(required = false, name = "--imap-username", usage = "Username for accessing OpenNMS IMAP alarms.")
    private String imapUsername = "username";

    @Option(required = false, name = "--imap-password", usage = "Password for accessing OpenNMS IMAP alarms.")
    private String imapPassword = "secret";

    @Option(required = false, name = "--imap-email", usage = "EMail for accessing OpenNMS IMAP alarms.")
    private String imapEmail = "imap-alarms@opennms.org";

    @Option(required = false, name = "--verbose", usage = "Verbose output.")
    private Boolean verbose = false;

    @Option(required = false, name = "--delay", usage = "Update delay in seconds.")
    private int delay = 15;

    private GreenMail greenMail;
    private GreenMailUser user;
    private MailFolder inbox;
    private ServerSetup serverSetup;
    private Client client;
    final Map<Integer, OnmsAlarm> alarmMap = new ConcurrentHashMap<>();
    final Map<Integer, Long> alarmUidMap = new ConcurrentHashMap<>();
    final ImapAlarmsFolderListener imapAlarmsFolderListener = new ImapAlarmsFolderListener(this);

    private ImapAlarms() {
    }

    private OnmsAlarmCollection getUnclearedAlarms() {
        return client.target(url).path("/api/v2/alarms")
                .queryParam("limit", "0")
                .queryParam("_s", "alarm.severity!=CLEARED")
                .request(MediaType.APPLICATION_JSON).get(OnmsAlarmCollection.class);
    }

    void clearAlarm(final int alarmId) {
        final Form form = new Form();
        form.param("clear", "true");

        client.target(url).path("/rest/alarms/" + alarmId)
                .request().put(Entity.form(form));
    }

    void acknowledgeAlarm(final int alarmId) {
        final Form form = new Form();
        form.param("ack", "true");

        client.target(url).path("/rest/alarms/" + alarmId)
                .request().put(Entity.form(form));
    }

    void unacknowledgeAlarm(final int alarmId) {
        final Form form = new Form();
        form.param("ack", "false");

        client.target(url).path("/rest/alarms/" + alarmId)
                .request().put(Entity.form(form));
    }

    private void loadDefaults() {
        final Properties properties = new Properties();
        try {
            if (isVerbose()) {
                System.out.println("Processing imap-alarms-standalone.properties...");
            }
            properties.load(new FileReader("imap-alarms-standalone.properties"));

            if (properties.containsKey("url")) {
                url = properties.getProperty("url");
            }

            if (properties.containsKey("username")) {
                username = properties.getProperty("username");
            }

            if (properties.containsKey("password")) {
                password = properties.getProperty("password");
            }

            if (properties.containsKey("imapPort")) {
                imapPort = Integer.valueOf(properties.getProperty("imapPort"));
            }

            if (properties.containsKey("imapEmail")) {
                imapEmail = properties.getProperty("imapEmail");
            }

            if (properties.containsKey("imapUsername")) {
                imapUsername = properties.getProperty("imapUsername");
            }

            if (properties.containsKey("imapPassword")) {
                imapPassword = properties.getProperty("imapPassword");
            }

            if (properties.containsKey("verbose")) {
                verbose = Boolean.valueOf(properties.getProperty("verbose"));
            }
        } catch (IOException e) {
            System.err.println("No imap-alarms-standalone.properties file for defaults found.");
        }
    }

    private void execute(final String args[]) {
        loadDefaults();

        final ParserProperties parserProperties = ParserProperties.defaults();
        parserProperties.withUsageWidth(80);
        final CmdLineParser parser = new CmdLineParser(this);

        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            System.err.println(e.getMessage());
            System.err.println("Usage: imap-alarms.sh [options...]\n");
            new CmdLineParser(new ImapAlarms()).printUsage(System.err);
            System.err.println();
            return;
        }

        final HttpAuthenticationFeature httpAuthenticationFeature = HttpAuthenticationFeature.basic(username, password);
        final SslConfigurator sslConfigurator = SslConfigurator.newInstance();

        final JacksonJsonProvider jacksonJsonProvider = new JacksonJaxbJsonProvider()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        this.client = ClientBuilder.newClient(
                new ClientConfig(jacksonJsonProvider)
        ).register(httpAuthenticationFeature);

        startImapServer();
    }

    private void startImapServer() {
        this.serverSetup = new ServerSetup(imapPort, "0.0.0.0", ServerSetup.PROTOCOL_IMAPS);
        this.greenMail = new GreenMail(serverSetup);
        this.user = greenMail.setUser(this.imapEmail, this.imapUsername, this.imapPassword);

        try {
            this.inbox = greenMail.getManagers().getImapHostManager().getInbox(user);
        } catch (FolderException e) {
            throw new RuntimeException(e);
        }

        try {
            greenMail.getManagers().getImapHostManager().createMailbox(user, "TRASH");
        } catch (AuthorizationException e) {
            throw new RuntimeException(e);
        } catch (FolderException e) {
            throw new RuntimeException(e);
        }

        if (isVerbose()) {
            System.out.println("Starting IMAP server...");
        }

        greenMail.start();

        updateAlarms();

        inbox.addListener(imapAlarmsFolderListener);

        while (true) {
            if (isVerbose()) {
                System.out.println("Sleeping for " + delay + " seconds...");
            }
            try {
                Thread.sleep(delay * 1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            updateAlarms();
        }
    }

    String clean(final String string) {
        return string.replaceAll("\\r|\\n", "").trim();
    }

    private void updateAlarms() {
        final Map<Integer, OnmsAlarm> newAlarmMap = getUnclearedAlarms().getObjects().stream().collect(Collectors.toMap(OnmsAlarm::getId, Function.identity()));

        final Set<Integer> alarmsToBeRemoved = new TreeSet<>(alarmMap.keySet());
        alarmsToBeRemoved.removeAll(newAlarmMap.keySet());

        int deleted = alarmsToBeRemoved.size();
        int updated = 0;
        int added = 0;

        for (final int alarmId : alarmsToBeRemoved) {
            if (alarmUidMap.containsKey(alarmId)) {
                final StoredMessage storedMessage = inbox.getMessage(alarmUidMap.get(alarmId));
                final Flags flags = storedMessage.getFlags();
                flags.add(Flags.Flag.DELETED);
                try {
                    if (isVerbose()) {
                        System.out.println("Deleting alarm message '" + clean(getAlarmForUid(storedMessage.getUid()).getLogMsg()) + "'");
                    }
                    inbox.replaceFlags(flags, storedMessage.getUid(), imapAlarmsFolderListener, true);
                } catch (FolderException e) {
                    System.err.println("Error setting message flags for alarm message " + clean(getAlarmForUid(storedMessage.getUid()).getLogMsg()) + "'.");
                    e.printStackTrace(System.err);
                } finally {
                    alarmUidMap.remove(alarmId);
                    alarmMap.remove(alarmId);
                }
            } else {
                System.err.println("Alarm message does not exist for alarm " + alarmId + ".");
            }
        }

        for (final Map.Entry<Integer, OnmsAlarm> entry : newAlarmMap.entrySet()) {
            if (alarmUidMap.containsKey(entry.getKey())) {
                final StoredMessage storedMessage = inbox.getMessage(alarmUidMap.get(entry.getKey()));

                boolean modified = false;

                final Flags flags = storedMessage.getFlags();
                if (flags.contains(Flags.Flag.SEEN) && !entry.getValue().isAcknowledged()) {
                    flags.remove(Flags.Flag.SEEN);
                    modified = true;
                } else if (!flags.contains(Flags.Flag.SEEN) && entry.getValue().isAcknowledged()) {
                    flags.add(Flags.Flag.SEEN);
                    modified = true;
                }

                if (modified) {
                    updated++;

                    try {
                        inbox.replaceFlags(flags, storedMessage.getUid(), imapAlarmsFolderListener, false);
                        if (isVerbose()) {
                            System.out.println("Updating alarm message: " + clean(entry.getValue().getLogMsg()));
                        }
                    } catch (FolderException e) {
                        System.err.println("Error setting message flags.");
                        e.printStackTrace(System.err);
                    }
                }
            } else {
                try {
                    OnmsAlarm alarm = entry.getValue();
                    String alarmUrl = url + "/alarm/detail.htm?id=";

                    final Session session = GreenMailUtil.getSession(serverSetup);
                    final MimeMessage mimeMessage = new MimeMessage(session);
                    final String subject = alarm.getSeverity() + ": " + clean(alarm.getLogMsg());
                    final String body = String.format("%s<br/><a href='%s'>%s</a>", alarm.getDescription(), alarmUrl + alarm.getId(), HtmlEscapers.htmlEscaper().escape(alarmUrl + alarm.getId()));

                    mimeMessage.setFlag(Flags.Flag.SEEN, alarm.isAcknowledged());
                    mimeMessage.setSubject(subject);
                    mimeMessage.setSentDate(alarm.getFirstEventTime());
                    mimeMessage.setHeader("From", "OpenNMS");
                    mimeMessage.setRecipients(Message.RecipientType.TO, this.imapEmail);
                    mimeMessage.setText(body, "us-ascii", "html");

                    user.deliver(mimeMessage);

                    final long uids[] = inbox.search(new MessageIDTerm(mimeMessage.getMessageID()));

                    if (uids.length == 1) {
                        added++;

                        alarmUidMap.put(alarm.getId(), uids[0]);
                        alarmMap.put(alarm.getId(), alarm);

                        if (isVerbose()) {
                            System.out.println("Added alarm message '" + clean(alarm.getLogMsg()) + "'");
                        }
                    } else {
                        System.err.println("Message-ID '" + mimeMessage.getMessageID() + "' was not found.");
                    }
                } catch (MessagingException e) {
                    System.err.println("Error constructing message.");
                    e.printStackTrace(System.err);
                }
            }
        }

        if (isVerbose()) {
            System.out.println(deleted + " alarm(s) deleted, " + updated + " alarm(s) updated, " + added + " alarm(s) added");
        }
    }

    public static void main(String[] args) {
        final ImapAlarms imapAlarms = new ImapAlarms();
        imapAlarms.execute(args);
    }

    MailFolder getInbox() {
        return inbox;
    }

    Long getUidForAlarmId(final int alarmId) {
        return alarmUidMap.get(alarmId);
    }

    Integer getAlarmIdForUid(final long uid) {
        for (Map.Entry<Integer, Long> entry : alarmUidMap.entrySet()) {
            if (entry.getValue().equals(uid)) {
                return entry.getKey();
            }
        }
        return null;
    }

    OnmsAlarm getAlarmForUid(final long uid) {
        Integer alarmId = getAlarmIdForUid(uid);

        if (alarmId == null) {
            return null;
        }

        return alarmMap.get(alarmId);
    }

    OnmsAlarm getAlarmForAlarmId(final int alarmId) {
        return alarmMap.get(alarmId);
    }

    boolean isVerbose() {
        return verbose;
    }

    Map<Integer, Long> getAlarmUidMap() {
        return alarmUidMap;
    }
}
