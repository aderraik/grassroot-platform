package za.org.grassroot.services.account;

import org.jivesoftware.smack.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specifications;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.org.grassroot.core.domain.*;
import za.org.grassroot.core.domain.notification.AccountBillingNotification;
import za.org.grassroot.core.enums.AccountLogType;
import za.org.grassroot.core.repository.AccountBillingRecordRepository;
import za.org.grassroot.core.repository.AccountRepository;
import za.org.grassroot.core.util.AfterTxCommitTask;
import za.org.grassroot.core.util.DateTimeUtil;
import za.org.grassroot.integration.email.EmailSendingBroker;
import za.org.grassroot.services.MessageAssemblingService;
import za.org.grassroot.services.util.LogsAndNotificationsBroker;
import za.org.grassroot.services.util.LogsAndNotificationsBundle;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

import static java.time.temporal.ChronoUnit.DAYS;
import static za.org.grassroot.services.specifications.AccountSpecifications.nextStatementBefore;
import static za.org.grassroot.services.specifications.NotificationSpecifications.*;

/**
 * Created by luke on 2016/10/25.
 */
@Service
public class AccountBillingBrokerImpl implements AccountBillingBroker {

    private static final Logger log = LoggerFactory.getLogger(AccountBillingBrokerImpl.class);

    private static final String SYSTEM_USER = "system_user"; // fake user since user_uid is null and these batch jobs are automated
    private static final int DEFAULT_MONTH_LENGTH = 30;
    private static final Duration PAYMENT_INTERVAL = Duration.ofHours(1L);

    protected static final ZoneOffset BILLING_TZ = ZoneOffset.UTC;
    protected static final LocalTime STD_BILLING_HOUR = LocalTime.of(10, 0);

    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountBillingRecordRepository billingRepository;
    @Autowired private LogsAndNotificationsBroker logsAndNotificationsBroker;
    @Autowired private MessageAssemblingService messageAssemblingService;
    @Autowired private ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    private EmailSendingBroker emailSendingBroker;

    @Override
    @Transactional
    public AccountBillingRecord generateSignUpBill(String accountUid) {
        Account account = accountRepository.findOneByUid(accountUid);

        AccountBillingRecord lastRecord = billingRepository.findTopByAccountOrderByCreatedDateTimeDesc(account);

        if (lastRecord == null || lastRecord.getPaid()) {
            LogsAndNotificationsBundle bundle = new LogsAndNotificationsBundle();
            AccountLog billingLog = new AccountLog.Builder(account)
                    .userUid(SYSTEM_USER)
                    .accountLogType(AccountLogType.BILL_CALCULATED)
                    .billedOrPaid((long) account.getSubscriptionFee())
                    .description("Bill generated for initial account payment")
                    .build();
            bundle.addLog(billingLog);

            AccountBillingRecord record = new AccountBillingRecord.BillingBuilder(account)
                    .accountLog(billingLog)
                    .openingBalance(account.getOutstandingBalance())
                    .amountBilled((long) account.getSubscriptionFee())
                    .billedPeriodStart(Instant.now())
                    .billedPeriodEnd(Instant.now())
                    .statementDateTime(Instant.now())
                    .paymentDueDate(Instant.now())
                    .build();

            account.setOutstandingBalance(account.getSubscriptionFee());

            logsAndNotificationsBroker.storeBundle(bundle);
            return billingRepository.save(record);
        } else {
            return lastRecord;
        }
    }

    @Override
    @Transactional
    public AccountBillingRecord generatePaymentChangeBill(String accountUid, long amountToCharge) {
        Account account = accountRepository.findOneByUid(accountUid);

        log.info("Generating payment change bill, amount is : " + amountToCharge);

        LogsAndNotificationsBundle bundle = new LogsAndNotificationsBundle();
        AccountLog billingLog = new AccountLog.Builder(account)
                .userUid(SYSTEM_USER)
                .accountLogType(AccountLogType.BILL_CALCULATED)
                .billedOrPaid(amountToCharge)
                .description("Bill generated when switching payment methods")
                .build();
        bundle.addLog(billingLog);

        // todo : if account has a non-zero balance that is more than R10, just charge that (incorporate in billing failure flow)
        AccountBillingRecord record = new AccountBillingRecord.BillingBuilder(account)
                .accountLog(billingLog)
                .openingBalance(account.getOutstandingBalance())
                .amountBilled(amountToCharge)
                .billedPeriodStart(Instant.now())
                .billedPeriodEnd(Instant.now())
                .statementDateTime(Instant.now())
                .paymentDueDate(Instant.now())
                .build();

        record.setTotalAmountToPay(amountToCharge);

        // note : do not change account balance as this payment is not for sub, and hence should put account in credit

        logsAndNotificationsBroker.storeBundle(bundle);
        return billingRepository.save(record);
    }

    @Override
    @Transactional
    public void generateClosingBill(String userUid, String accountUid) {
        Account account = accountRepository.findOneByUid(accountUid);

        boolean hasPaidPrior = billingRepository.countByAccountAndPaidTrue(account) > 0;
        if (hasPaidPrior) {
            Instant lastBilledDate = billingRepository.findTopByAccountOrderByCreatedDateTimeDesc(account).getBilledPeriodEnd();
            long amountToPay = calculateBillSinceLastDate(account);

            log.info("Creating bill for period from {} till now, for {}", lastBilledDate, amountToPay);

            LogsAndNotificationsBundle bundle = new LogsAndNotificationsBundle();
            AccountLog billingLog = new AccountLog.Builder(account)
                    .userUid(userUid)
                    .accountLogType(AccountLogType.BILL_CALCULATED)
                    .billedOrPaid(amountToPay)
                    .description("Bill generated during account closing")
                    .build();
            bundle.addLog(billingLog);

            logsAndNotificationsBroker.storeBundle(new LogsAndNotificationsBundle());

            AccountBillingRecord record = new AccountBillingRecord.BillingBuilder(account)
                    .accountLog(billingLog)
                    .openingBalance(account.getOutstandingBalance())
                    .amountBilled(amountToPay)
                    .billedPeriodStart(lastBilledDate)
                    .billedPeriodEnd(Instant.now())
                    .statementDateTime(Instant.now())
                    .paymentDueDate(Instant.now())
                    .build();

            logsAndNotificationsBroker.storeBundle(bundle);
        }
    }

    @Override
    @Transactional
    public void calculateAccountStatements(boolean sendEmails, boolean sendNotifications) {

        List<Account> validAccounts = accountRepository.findAll((nextStatementBefore(Instant.now())));

        log.info("Calculating monthly statements for {} accounts", validAccounts.size());

        List<AccountBillingRecord> records = new ArrayList<>();
        LogsAndNotificationsBundle bundle = new LogsAndNotificationsBundle();

        for (Account account : validAccounts) {
            log.debug("Calculating bill and cost for account : {}", account.getAccountName());
            AccountBillingRecord lastBill = billingRepository.findTopByAccountOrderByCreatedDateTimeDesc(account);

            /*
            * Logic : billing starts at the last statement end date, if there is a last statement; if not, it defaults to
            * the account enabled date, or one month ago, whichever is later
            * Note: by default when an account is enabled a billing record is generated & the cycle defaults to monthly, so
            * the other two cases should not occur, but accommodating possible corner cases
            */

            log.info("Last bill was up until : {}", lastBill != null ? lastBill.getBilledPeriodEnd() : "null");
            LocalDateTime monthAgo = LocalDateTime.now().minusMonths(1L);
            LocalDateTime billingStart = (lastBill != null) ? LocalDateTime.ofInstant(lastBill.getBilledPeriodEnd(), BILLING_TZ)
                    : account.getEnabledDateTime().isBefore(monthAgo.toInstant(BILLING_TZ)) ? monthAgo
                    : LocalDateTime.ofInstant(account.getEnabledDateTime(), BILLING_TZ);

            LocalDateTime billingEnd = Instant.now().isBefore(account.getDisabledDateTime()) ?
                    LocalDateTime.now() : LocalDateTime.ofInstant(account.getDisabledDateTime(), BILLING_TZ);

            log.debug("Calculating bill and cost between : {} and {}", billingStart, billingEnd);

            long billForPeriod = calculateAccountBillSinceLastStatement(account, billingStart, billingEnd);
            long costForPeriod = calculateAccountCostsInPeriod(account, billingStart, billingEnd);

            log.debug("Okay, calculated bill = {}, cost = {}", billForPeriod, costForPeriod);

            AccountLog billingLog = new AccountLog.Builder(account)
                    .userUid(SYSTEM_USER)
                    .accountLogType(AccountLogType.BILL_CALCULATED)
                    .billedOrPaid(billForPeriod)
                    .build();
            bundle.addLog(billingLog);

            AccountBillingRecord record = new AccountBillingRecord.BillingBuilder(account)
                    .accountLog(billingLog)
                    .openingBalance(account.getOutstandingBalance())
                    .amountBilled(billForPeriod)
                    .billedPeriodStart(billingStart.toInstant(BILLING_TZ))
                    .billedPeriodEnd(billingEnd.toInstant(BILLING_TZ))
                    .statementDateTime(Instant.now())
                    .paymentDueDate(Instant.now().plus(PAYMENT_INTERVAL))
                    .build();
            records.add(record);

            bundle.addLog(new AccountLog.Builder(account)
                    .userUid(SYSTEM_USER)
                    .accountLogType(AccountLogType.COST_CALCULATED)
                    .billedOrPaid(costForPeriod).build());

            if (sendNotifications) {
                bundle.addNotifications(generateStatementNotifications(record));
            }

            account.setNextBillingDate(LocalDateTime.now().plusMonths(1L).toInstant(ZoneOffset.UTC));
            account.addToBalance(billForPeriod);

            log.info("Set account next billing date : " + account.getNextBillingDate());
        }

        if (sendEmails && !records.isEmpty()) {
            AfterTxCommitTask afterTxCommitTask = () -> processAccountStatementEmails(
                    records.stream().map(AccountBillingRecord::getUid).collect(Collectors.toSet()));
            eventPublisher.publishEvent(afterTxCommitTask);
        }

        logsAndNotificationsBroker.storeBundle(bundle);
        billingRepository.save(records);
    }

    // major todo: handle case of account type / fee changing during the month
    private long calculateAccountBillSinceLastStatement(Account account, LocalDateTime billingPeriodStart, LocalDateTime billingPeriodEnd) {
        // note : be careful about not running this around midnight, or date calcs could get messy / false (and keep an eye on floating points)
        if (DateTimeUtil.areDatesOneMonthApart(billingPeriodStart, billingPeriodEnd)) {
            return account.getSubscriptionFee();
        } else {
            double proportionOfMonth = (double) (DAYS.between(billingPeriodStart, billingPeriodEnd)) / (double) DEFAULT_MONTH_LENGTH;
            log.info("Proportion of month: {}", proportionOfMonth);
            return (long) Math.ceil(proportionOfMonth * (double) account.getSubscriptionFee());
        }
    }

    private long calculateBillSinceLastDate(Account account) {
        Instant lastBilledDate = billingRepository.findTopByAccountOrderByCreatedDateTimeDesc(account).getBilledPeriodEnd();
        double proportionOfMonth = ((double) Duration.between(lastBilledDate, Instant.now()).toDays()) / (double) DEFAULT_MONTH_LENGTH;
        return (long) Math.ceil(proportionOfMonth * (double) account.getSubscriptionFee());
    }

    private long calculateAccountCostsInPeriod(Account account, LocalDateTime billingPeriodStart, LocalDateTime billingPeriodEnd) {
        Instant periodStart = billingPeriodStart.toInstant(BILLING_TZ);
        Instant periodEnd = billingPeriodEnd.toInstant(BILLING_TZ);

        if (account.getDisabledDateTime().isBefore(periodStart)) {
            return 0;
        }

        // todo : watch Hibernate on this for excessive DB calls (though this is, for the moment, an infrequent batch call)
        Set<PaidGroup> paidGroups = account.getPaidGroups();
        final int messageCost = account.getFreeFormCost();

        Specifications<Notification> notificationCounter = Specifications.where(wasDelivered())
                .and(createdTimeBetween(periodStart, periodEnd))
                .and(belongsToAccount(account));

        long costAccumulator = logsAndNotificationsBroker.countNotifications(notificationCounter) * messageCost;

        for (PaidGroup paidGroup : paidGroups) {
            costAccumulator += (countMessagesForPaidGroup(paidGroup, periodStart, periodEnd) * messageCost);
        }

        return costAccumulator;
    }

    private long countMessagesForPaidGroup(PaidGroup paidGroup, Instant periodStart, Instant periodEnd) {
        Group group = paidGroup.getGroup();
        Instant start = paidGroup.getActiveDateTime().isBefore(periodStart) ? periodStart : paidGroup.getActiveDateTime();
        Instant end = paidGroup.getExpireDateTime() == null || paidGroup.getExpireDateTime().isAfter(periodEnd) ?
                periodEnd : paidGroup.getExpireDateTime();

        Specifications<Notification> specifications = Specifications.where(wasDelivered())
                .and(createdTimeBetween(start, end))
                .and(ancestorGroupIs(group));

        return logsAndNotificationsBroker.countNotifications(specifications);
    }

    private Set<Notification> generateStatementNotifications(AccountBillingRecord billingRecord) {
        Set<Notification> notifications = new HashSet<>();
        for (User user : billingRecord.getAccount().getAdministrators()) {
            AccountBillingNotification notification = new AccountBillingNotification(user,
                    messageAssemblingService.createAccountBillingNotification(billingRecord),
                    billingRecord.getAccountLog());
            notification.setNextAttemptTime(Instant.now()); // make sure it's tomorrow morning ...
            notifications.add(notification);
        }
        return notifications;
    }

    @Override
    @Transactional(readOnly = true)
    public void processAccountStatementEmails(Set<String> billingRecordUids) {
        Objects.requireNonNull(billingRecordUids);
        Set<AccountBillingRecord> records = billingRepository.findByUidIn(billingRecordUids);
        for (AccountBillingRecord record : records) {
            log.debug("generating account statement for {}, amount of {}", record.getAccount().getAccountName(), record.getTotalAmountToPay());
            if (!StringUtils.isEmpty(record.getAccount().getBillingUser().getEmailAddress())) {
                final String emailSubject = messageAssemblingService.createAccountStatementSubject(record);
                final String emailBody = messageAssemblingService.createAccountStatementEmail(record);
                emailSendingBroker.generateAndSendBillingEmail(emailSubject, emailBody, record.getUid());
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountBillingRecord> fetchBillingRecords(String accountUid, Sort sort) {
        Account account = accountRepository.findOneByUid(accountUid);
        return billingRepository.findByAccount(account, sort);
    }

    @Override
    public AccountBillingRecord fetchRecordByPayment(String paymentId) {
        return billingRepository.findOneByPaymentId(paymentId);
    }

}