package za.org.grassroot.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import za.org.grassroot.core.domain.Account;
import za.org.grassroot.integration.email.EmailSendingBroker;
import za.org.grassroot.integration.email.GrassrootEmail;
import za.org.grassroot.services.account.AccountBillingBroker;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Created by luke on 2016/10/25.
 */
@Component
@ConditionalOnProperty(name = "grassroot.email.enabled", havingValue = "true",  matchIfMissing = false)
public class ScheduledEmailTasks {

    private static final Logger logger = LoggerFactory.getLogger(ScheduledEmailTasks.class);

    @Value("${grassroot.daily.admin.email:false}")
    private boolean sendDailyAdminMail;

    private AccountBillingBroker accountBroker;
    private EmailSendingBroker emailSendingBroker;

    @Autowired
    public ScheduledEmailTasks(AccountBillingBroker accountBroker, EmailSendingBroker emailSendingBroker) {
        this.accountBroker = accountBroker;
        this.emailSendingBroker = emailSendingBroker;
    }

    @Scheduled(fixedRate = 1000 * 60 * 60 * 24)
    public void sendSystemStatsEmail() {
        if (sendDailyAdminMail) {
            logger.info("Sending system stats email ... ");
            emailSendingBroker.sendSystemStatusMail(new GrassrootEmail.EmailBuilder("System Email")
                    .content("Hello this is a system email, it will soon have stats and so on").build());
        }
    }


}
