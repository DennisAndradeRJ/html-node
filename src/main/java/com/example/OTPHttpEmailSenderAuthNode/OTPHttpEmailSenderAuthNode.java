/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2017-2018 ForgeRock AS.
 */


package com.example.OTPHttpEmailSenderAuthNode;

import static org.forgerock.openam.auth.node.api.SharedStateConstants.EMAIL_ADDRESS;
import static org.forgerock.openam.auth.node.api.SharedStateConstants.ONE_TIME_PASSWORD;
import static org.forgerock.openam.auth.node.api.SharedStateConstants.REALM;
import static org.forgerock.openam.auth.node.api.SharedStateConstants.USERNAME;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

import com.sun.identity.sm.RequiredValueValidator;
import org.forgerock.json.JsonValue;
import org.forgerock.openam.annotations.sm.Attribute;
import org.forgerock.openam.auth.node.api.Action;
import org.forgerock.openam.auth.node.api.Node;
import org.forgerock.openam.auth.node.api.NodeProcessException;
import org.forgerock.openam.auth.node.api.SingleOutcomeNode;
import org.forgerock.openam.auth.node.api.TreeContext;
import org.forgerock.openam.auth.nodes.IdentityProvider;
import org.forgerock.openam.auth.nodes.SmtpBaseConfig;
import org.forgerock.openam.core.CoreWrapper;
import org.forgerock.openam.utils.CollectionUtils;
import org.forgerock.openam.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

import com.google.inject.assistedinject.Assisted;
import com.iplanet.sso.SSOException;
import com.sun.identity.idm.AMIdentity;
import com.sun.identity.idm.IdRepoException;

import javax.mail.*;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

/**
 * A node that checks to see if zero-page login headers have specified username and whether that username is in a group
 * permitted to use zero-page login headers.
 */
@Node.Metadata(outcomeProvider  = SingleOutcomeNode.OutcomeProvider.class,
               configClass      = OTPHttpEmailSenderAuthNode.Config.class)
public class OTPHttpEmailSenderAuthNode extends SingleOutcomeNode {

    private static final String BUNDLE = OTPHttpEmailSenderAuthNode.class.getName()
            .replace('.', '/');
    private final Logger logger = LoggerFactory.getLogger("amAuth");
    private final Config config;
    private final CoreWrapper coreWrapper;
    private final IdentityProvider identityProvider;

    /**
     * Configuration for the node.
     */
    public interface Config extends SmtpBaseConfig {
        /**
         * The key used to look up the email address in an identity.
         *
         * @return email address attribute.
         */
        @Attribute(order = 1100)
        default String emailAttribute() {
            return "mail";
        }

        /**
         * Html validation method.
         * @return the Html validation method.
         */
        @Attribute(order = 1200, validators = {RequiredValueValidator.class})
        default HtmlValidationMethod htmlValidationMethod() {
            return HtmlValidationMethod.INLINE;
        }

        /**
         * The html validation value.
         * @return the html validation value.
         */
        @Attribute(order = 1300, validators = {RequiredValueValidator.class})
        String htmlValidationValue();
    }


    /**
     * Create the node using Guice injection. Just-in-time bindings can be used to obtain instances of other classes
     * from the plugin.
     *
     * @param config The service config.
     */
    @Inject
    public OTPHttpEmailSenderAuthNode(@Assisted Config config, CoreWrapper coreWrapper, IdentityProvider identityProvider) {
        this.config = config;
        this.coreWrapper = coreWrapper;
        this.identityProvider = identityProvider;
    }

    @Override
    public Action process(TreeContext context) throws NodeProcessException {
        logger.debug("OneTimePasswordSmtpSenderNode started");

        ResourceBundle bundle = context.request.locales.getBundleInPreferredLocale(BUNDLE, getClass().getClassLoader());
        if (!context.sharedState.isDefined(EMAIL_ADDRESS)) {
            String username = context.sharedState.get(USERNAME).asString();
            String realm = coreWrapper.convertRealmPathToRealmDn(context.sharedState.get(REALM).asString());
            AMIdentity identity = getAmIdentity(username, bundle, realm);

            context.sharedState.add(EMAIL_ADDRESS, getToEmailAddress(identity, username, bundle));
        }
        //String oneTimePassword = context.sharedState.get(ONE_TIME_PASSWORD).asString();
        JsonValue oneTimePassword = context.getState(ONE_TIME_PASSWORD);
        if (oneTimePassword == null) {
            logger.warn("oneTimePasswordNotFound");
            throw new NodeProcessException(bundle.getString("oneTimePassword.not.found"));
        }
        sendEmail(bundle, context.sharedState.get(EMAIL_ADDRESS).asString(), oneTimePassword.asString());

        return goToNext().replaceSharedState(context.sharedState.copy()).build();
    }

    private void sendEmail(ResourceBundle bundle, String toEmailAddress,
                           String oneTimePassword) throws NodeProcessException {
        try {
            logger.debug("Sending one time password from {}, to {}", config.fromEmailAddress(), toEmailAddress);
            if (config.htmlValidationMethod().equals(HtmlValidationMethod.INLINE)) {
                sendHtmlEmail(config.hostName(), String.valueOf(config.hostPort()), config.username(), String.valueOf(config.password().get()), toEmailAddress, bundle.getString("messageSubject"),
                        config.htmlValidationValue(), oneTimePassword);
            } else if (config.htmlValidationMethod().equals(HtmlValidationMethod.FILE_BASED)) {
                String body = "";
                if (isUrlValid(config.htmlValidationValue()))
                    body = getStringFromFile();
                sendHtmlEmail(config.hostName(), String.valueOf(config.hostPort()), config.username(), String.valueOf(config.password().get()), toEmailAddress, bundle.getString("messageSubject"),
                        body, oneTimePassword);
            }
        } catch (Exception e) {
            logger.warn("Email sending failure", e);
            throw new NodeProcessException(bundle.getString("send.failure"), e);
        }
    }

    private String getStringFromFile () throws IOException {
        // Instantiate the URL class
        URL url = new URL(config.htmlValidationValue());
        // retrieve the content of the file
        Scanner sc = new Scanner (url.openStream());
        //Instantiate the StringBuffer class to hold the result
        StringBuffer sb = new StringBuffer();
        while (sc.hasNext()) {
            sb.append(sc.next() + " ");
        }
        return sb.toString();
    }

    private boolean isUrlValid (String url) {
        try {
            HttpURLConnection.setFollowRedirects(false);
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(1000);
            con.setReadTimeout(1000);
            con.setRequestMethod("HEAD");
            return (con.getResponseCode() == HttpURLConnection.HTTP_OK);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private String getToEmailAddress(AMIdentity identity, String username,
                                     ResourceBundle bundle) throws NodeProcessException {
        String toEmailAddress;
        try {
            toEmailAddress = getEmailAddress(identity, username);
            if (toEmailAddress == null) {
                logger.warn("Email not found");
                throw new NodeProcessException(bundle.getString("email.not.found"));
            }
        } catch (IdRepoException | SSOException e) {
            logger.warn("Email lookup failure", e);
            throw new NodeProcessException(bundle.getString("email.lookup.failure"), e);
        }
        return toEmailAddress;
    }

    private AMIdentity getAmIdentity(String username, ResourceBundle bundle, String realm) throws NodeProcessException {
        AMIdentity identity;
        try {
            identity = identityProvider.getIdentity(username, realm);
        } catch (IdRepoException | SSOException e) {
            logger.warn("Identity lookup failure", e);
            throw new NodeProcessException(bundle.getString("identity.failure"), e);
        }
        return identity;
    }


    /**
     * Gets the Email address of the user.
     *
     * @param identity The user's identity.
     * @param userName the username used to look up the identity
     * @return The user's email address.
     * @throws IdRepoException If there is a problem getting the user's email address.
     * @throws SSOException    If there is a problem getting the user's email address.
     */
    private String getEmailAddress(AMIdentity identity, String userName) throws IdRepoException, SSOException {
        String emailAttribute = config.emailAttribute();
        if (StringUtils.isBlank(emailAttribute)) {
            emailAttribute = "mail";
        }

        logger.debug("Using email attribute of {}", emailAttribute);

        Set<String> emails = identity.getAttribute(emailAttribute);
        String mail = null;

        if (CollectionUtils.isNotEmpty(emails)) {
            mail = emails.iterator().next();
            logger.debug("Email address found {} with username : {}", mail, userName);
        } else {
            logger.debug("no email found with username : {}", userName);
        }

        return mail;
    }

    // New method to send HTML email
    private void sendHtmlEmail(String host, String port,
                               final String userName, final String password, String toAddress,
                               String subject, String message, String oneTimePassword) throws AddressException,
            MessagingException {

        // sets SMTP server properties
        Properties properties = new Properties();
        properties.put("mail.smtp.host", host);
        properties.put("mail.smtp.port", port);
        properties.put("mail.smtp.auth", "true");
        properties.put("mail.smtp.starttls.enable", "true");

        // creates a new session with an authenticator
        Authenticator auth = new Authenticator() {
            public PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(userName, password);
            }
        };

        Session session = Session.getInstance(properties, auth);

        // creates a new e-mail message
        Message msg = new MimeMessage(session);

        msg.setFrom(new InternetAddress(userName));
        InternetAddress[] toAddresses = { new InternetAddress(toAddress) };
        msg.setRecipients(Message.RecipientType.TO, toAddresses);
        msg.setSubject(subject);
        msg.setSentDate(new Date());
        // set plain text message
        msg.setContent(message + " \n \n Your one time passcode is: " + oneTimePassword, "text/html");

        // sends the e-mail
        Transport.send(msg);
    }

    /**
     * Which way will the HTTP OTP sender node get the body of the email.
     */
    public enum HtmlValidationMethod {
        /**
         * Retrieve the body from a file.
         */
        FILE_BASED,
        /**
         * Use inline body
         */
        INLINE
    }
}
