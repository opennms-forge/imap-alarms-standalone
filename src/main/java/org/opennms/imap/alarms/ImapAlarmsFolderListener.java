package org.opennms.imap.alarms;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.mail.Flags;

import org.opennms.netmgt.model.OnmsAlarm;

import com.icegreen.greenmail.store.FolderListener;

public class ImapAlarmsFolderListener implements FolderListener {
    private ImapAlarms imapAlarms;

    public ImapAlarmsFolderListener(final ImapAlarms imapAlarms) {
        this.imapAlarms = imapAlarms;
    }

    @Override
    public void expunged(final int msn) {
        final Set<Long> existingUids = Arrays.stream(imapAlarms.getInbox().getMessageUids()).boxed().collect(Collectors.toSet());
        for (Map.Entry<Integer, Long> entry : imapAlarms.getAlarmUidMap().entrySet()) {
            if (!existingUids.contains(entry.getValue())) {
                if (imapAlarms.isVerbose()) {
                    System.out.println("EXPUNGED: Message '" + imapAlarms.clean(imapAlarms.getAlarmForAlarmId(entry.getKey()).getLogMsg()) + "' -> Clearing Alarm");
                }
                imapAlarms.clearAlarm(entry.getKey());
            }
        }
    }

    @Override
    public void added(final int msn) {
    }

    @Override
    public void flagsUpdated(final int msn, final Flags flags, final Long uid) {
        final OnmsAlarm onmsAlarm = imapAlarms.getAlarmForUid(uid);

        if (onmsAlarm == null) {
            System.err.println("Flags updated for an unassociated message.");
            return;
        }

        if (flags.contains(Flags.Flag.SEEN)) {
            if (imapAlarms.isVerbose()) {
                System.out.println("Flag updated (SEEN) for message '" + imapAlarms.clean(onmsAlarm.getLogMsg()) + "' -> Acknowledging Alarm");
            }
            imapAlarms.acknowledgeAlarm(onmsAlarm.getId());
        } else {
            if (imapAlarms.isVerbose()) {
                System.out.println("Flag updated (UNSEEN) for message '" + imapAlarms.clean(onmsAlarm.getLogMsg()) + "' -> Unacknowledging Alarm");
            }
            imapAlarms.unacknowledgeAlarm(onmsAlarm.getId());
        }

        if (flags.contains(Flags.Flag.DELETED)) {
            if (imapAlarms.isVerbose()) {
                System.out.println("Flag updated (DELETED) for message '" + imapAlarms.clean(onmsAlarm.getLogMsg()) + "' -> Clearing Alarm");
                imapAlarms.clearAlarm(onmsAlarm.getId());
            }
        }
    }

    @Override
    public void mailboxDeleted() {
    }
}
