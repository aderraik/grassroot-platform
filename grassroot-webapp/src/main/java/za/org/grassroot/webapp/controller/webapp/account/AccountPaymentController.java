package za.org.grassroot.webapp.controller.webapp.account;

import org.apache.commons.validator.routines.EmailValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import za.org.grassroot.core.domain.Account;
import za.org.grassroot.core.domain.AccountBillingRecord;
import za.org.grassroot.core.domain.User;
import za.org.grassroot.core.enums.AccountBillingCycle;
import za.org.grassroot.core.enums.AccountPaymentType;
import za.org.grassroot.core.enums.AccountType;
import za.org.grassroot.integration.exception.PaymentMethodFailedException;
import za.org.grassroot.integration.payments.PaymentBroker;
import za.org.grassroot.integration.payments.PaymentMethod;
import za.org.grassroot.integration.payments.PaymentResponse;
import za.org.grassroot.integration.payments.PaymentResultType;
import za.org.grassroot.services.account.AccountBillingBroker;
import za.org.grassroot.services.account.AccountBroker;
import za.org.grassroot.webapp.controller.BaseController;

import javax.servlet.http.HttpServletRequest;
import java.text.DecimalFormat;
import java.util.Map;

/**
 * Created by luke on 2016/11/25.
 */
@Controller
@RequestMapping("/account/payment")
@SessionAttributes("user")
@PropertySource(value = "${grassroot.payments.properties}", ignoreResourceNotFound = true)
public class AccountPaymentController extends BaseController {

    private static final Logger logger = LoggerFactory.getLogger(AccountPaymentController.class);

    private static long PAYMENT_VERIFICATION_AMT = 1000; // todo : make proportional to account size
    private static final int ENABLE = 100;
    private static final int UPDATE = 200;

    @Value("${grassroot.payments.auth.path:/auth/incoming}")
    private String authorizationPath;
    @Value("${grassroot.payments.deposit.details:DepositDetails}")
    private String depositDetails;
    @Value("${grassroot.payments.email.address:payments@grassroot.org.za}")
    private String paymentsEmail;

    private AccountBroker accountBroker;
    private AccountBillingBroker accountBillingBroker;
    private PaymentBroker paymentBroker;

    @Autowired
    public AccountPaymentController(AccountBroker accountBroker, AccountBillingBroker accountBillingBroker,
                                    PaymentBroker paymentBroker) {
        this.accountBroker = accountBroker;
        this.accountBillingBroker = accountBillingBroker;
        this.paymentBroker = paymentBroker;
    }

    @RequestMapping(value = "create", method = RequestMethod.POST)
    public String createAccountEntity(Model model, @RequestParam(required = false) String accountName, @RequestParam AccountType accountType,
                                      @RequestParam(value = "emailAddress", required = false) String emailAddress,
                                      @RequestParam AccountBillingCycle billingCycle, @RequestParam(required = false) AccountPaymentType paymentType) {

        final String nameToUse = StringUtils.isEmpty(accountName) ? getUserProfile().nameToDisplay() : accountName;
        final String accountUid = accountBroker.createAccount(getUserProfile().getUid(), nameToUse, getUserProfile().getUid(), accountType,
                paymentType == null ? AccountPaymentType.CARD_PAYMENT : paymentType, billingCycle);

        if (!StringUtils.isEmpty(emailAddress) && EmailValidator.getInstance(false).isValid(emailAddress)) {
            userManagementService.updateEmailAddress(getUserProfile().getUid(), emailAddress);
        }

        refreshAuthorities();
        Account createdAccount = accountBroker.loadAccount(accountUid);

        if (AccountPaymentType.DIRECT_DEPOSIT.equals(paymentType)) {
            return loadDebitInstruction(model, createdAccount);
        } else {
            return loadCreditCardForm(model, createdAccount, true);
        }
    }

    @RequestMapping(value = "process", method = RequestMethod.POST)
    public String initiatePayment(Model model, RedirectAttributes attributes, @RequestParam String accountUid,
                                  @ModelAttribute("method") PaymentMethod paymentMethod, HttpServletRequest request) {
        AccountBillingRecord record = accountBillingBroker.generateSignUpBill(accountUid);
        return handleInitiatingPayment(accountUid, paymentMethod, record, ENABLE, model, attributes, request);
    }

    @RequestMapping(value = "redirect", method = RequestMethod.GET)
    public String asyncPaymentDone(@RequestParam String paymentId, @RequestParam(required = false) String paymentRef,
                                   @RequestParam boolean succeeded, @RequestParam(required = false) String failureDescription,
                                   Model model) {
        model.addAttribute("paymentId", paymentId);
        model.addAttribute("paymentRef", paymentRef);
        model.addAttribute("succeeded", succeeded);
        model.addAttribute("failureDescription", failureDescription);
        return "account/done_redirect";
    }

    @RequestMapping(value = "done", method = RequestMethod.POST)
    public String asyncPaymentTop(@RequestParam String paymentId, @RequestParam(required = false) String paymentRef,
                                  @RequestParam boolean succeeded, @RequestParam(required = false) String failureDescription,
                                  RedirectAttributes attributes, HttpServletRequest request) {
        AccountBillingRecord record = accountBillingBroker.fetchRecordByPayment(paymentId);
        Account account = record.getAccount();
        int typeOfCall = account.isEnabled() ? UPDATE : ENABLE;
        if (succeeded) {
            return handleSuccess(paymentId, paymentRef, typeOfCall, attributes, request);
        } else {
            return handleError(typeOfCall, attributes, request, failureDescription);
        }
    }

    @RequestMapping(value = "retry", method = RequestMethod.GET)
    public String retryAccountPayment(Model model, @RequestParam(required = false) String errorDescription,
                                      @RequestParam(required = false) AccountPaymentType paymentType) {
        User user = userManagementService.load(getUserProfile().getUid());
        Account account = user.getAccountAdministered();

        if (account == null) {
            throw new AccessDeniedException("Must have an account before trying to retry payment");
        }

        if (AccountPaymentType.DIRECT_DEPOSIT.equals(paymentType)) {
            return loadDebitInstruction(model, account);
        } else {
            if (!StringUtils.isEmpty(errorDescription)) {
                model.addAttribute("errorDescription", errorDescription);
            }
            return loadCreditCardForm(model, account, true);
        }
    }

    @PreAuthorize("hasRole('ROLE_ACCOUNT_ADMIN')")
    @RequestMapping(value = "change", method = RequestMethod.GET)
    public String changePaymentCard(Model model, @RequestParam(required = false) String accountUid,
                                    @RequestParam(required = false) String errorDescription) {
        Account account = StringUtils.isEmpty(accountUid) ? accountBroker.loadUsersAccount(getUserProfile().getUid(), false) :
                accountBroker.loadAccount(accountUid);

        if (!StringUtils.isEmpty(errorDescription)) {
            model.addAttribute("errorDescription", errorDescription);
        }

        return loadCreditCardForm(model, account, false);
    }

    private String loadCreditCardForm(Model model, Account account, boolean newAccount) {
        model.addAttribute("account", account);
        model.addAttribute("newAccount", newAccount);
        model.addAttribute("method", PaymentMethod.makeEmpty());
        model.addAttribute("billingAmount", "R" + (new DecimalFormat("#,###.##").format(calculateAmountToPay(account))));
        return "account/payment";
    }

    private String loadDebitInstruction(Model model, Account account) {
        model.addAttribute("reference", account.getAccountName());
        model.addAttribute("details", depositDetails);
        model.addAttribute("paymentsEmail", paymentsEmail);
        model.addAttribute("billingAmount", "R" + (new DecimalFormat("#,###.##").format(calculateAmountToPay(account))));
        return "account/deposit";
    }

    private double calculateAmountToPay(Account account) {
        return Math.round((double) account.calculatePeriodCost() / 100);
    }

    @PreAuthorize("hasRole('ROLE_ACCOUNT_ADMIN')")
    @RequestMapping(value = "change", method = RequestMethod.POST)
    public String changePaymentDo(Model model, RedirectAttributes attributes, @RequestParam String accountUid,
                                  @ModelAttribute("method") PaymentMethod paymentMethod, HttpServletRequest request) {
        AccountBillingRecord record = accountBillingBroker.generatePaymentChangeBill(accountUid, PAYMENT_VERIFICATION_AMT);
        return handleInitiatingPayment(accountUid, paymentMethod, record, UPDATE, model, attributes, request);
    }

    private String handleInitiatingPayment(String accountUid, PaymentMethod method, AccountBillingRecord record,
                                           int enableOrUpdate, Model model, RedirectAttributes attributes, HttpServletRequest request) {
        final String returnUrl = "https://" + request.getServerName() + ":" + request.getServerPort() + authorizationPath;
        logger.info("sending payment request with this URL: {}", returnUrl);

        try {
            PaymentResponse response = paymentBroker.asyncPaymentInitiate(accountUid, method, record, returnUrl);
            if (!StringUtils.isEmpty(response.getRedirectUrl())) {
                for (Map<String, String> parameter : response.getRedirectParams()) {
                    attributes.addAttribute(parameter.get("name"), parameter.get("value"));
                }
                logger.info("Redirect Params: {}", response.getRedirectParams());
                model.addAttribute("redirectUrl", response.getRedirectUrl());
                model.addAttribute("redirectParams", response.getRedirectParams());
                return "account/payment_confirm";
            } else if (response.isSuccessful()) {
                return handleSuccess(response.getThisPaymentId(), response.getReference(), enableOrUpdate, attributes, request);
            } else {
                return handleError(enableOrUpdate, attributes, request, response.getDescription());
            }
        } catch (PaymentMethodFailedException e) {
            String description;
            if (e.getPaymentError() != null) {
                if (PaymentResultType.NOT_IN_3D.equals(e.getPaymentError().getResult().getType())) {
                    description = getMessage("account.payment.not3d");
                } else {
                    description = e.getPaymentError().getResult().getDescription();
                }
            } else {
                description = null;
            }
            return handleError(enableOrUpdate, attributes, request, description);
        }
    }

    private String handleSuccess(String paymentId, String paymentRef, int enableOrUpdateAccount,
                                 RedirectAttributes attributes, HttpServletRequest request) {
        AccountBillingRecord record = accountBillingBroker.fetchRecordByPayment(paymentId);
        Account account = record.getAccount();
        if (enableOrUpdateAccount == ENABLE) {
            accountBroker.enableAccount(getUserProfile().getUid(), account.getUid(), paymentRef);
            addMessage(attributes, MessageType.SUCCESS, "account.signup.payment.done", request);
        } else if (enableOrUpdateAccount == UPDATE) {
            accountBroker.updateAccountPaymentReference(getUserProfile().getUid(), account.getUid(), paymentRef);
            addMessage(attributes, MessageType.SUCCESS, "account.payment.changed", request);
        }
        attributes.addAttribute("accountUid", account.getUid());
        return "redirect:/account/view";
    }

    private String handleError(int enableOrUpdate, RedirectAttributes attributes, HttpServletRequest request, String description) {
        logger.info("Error in payment, handling with description: {}", description);
        addMessage(attributes, MessageType.ERROR, "account.payment.failed", request);
        attributes.addFlashAttribute("errorDescription", description == null ? "" : description);
        return enableOrUpdate == ENABLE ? "redirect:/account/payment/signup/retry" : "redirect:/account/payment/change";
    }

}
