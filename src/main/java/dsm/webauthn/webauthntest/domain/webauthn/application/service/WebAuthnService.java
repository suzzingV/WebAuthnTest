package dsm.webauthn.webauthntest.domain.webauthn.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.Serializers;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.exception.DataConversionException;
import com.webauthn4j.converter.jackson.WebAuthnJSONModule;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.Challenge;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import com.webauthn4j.verifier.exception.VerificationException;
import dsm.webauthn.webauthntest.domain.user.domain.entity.User;
import dsm.webauthn.webauthntest.domain.user.infrastructure.UserRepository;
import dsm.webauthn.webauthntest.domain.webauthn.domain.entity.Credential;
import dsm.webauthn.webauthntest.domain.webauthn.domain.entity.PubKeyCredParam;
import dsm.webauthn.webauthntest.domain.webauthn.exception.WebAuthnException;
import dsm.webauthn.webauthntest.domain.webauthn.infrastructure.CredentialRepository;
import dsm.webauthn.webauthntest.domain.webauthn.infrastructure.PubKeyCredParamRepository;
import dsm.webauthn.webauthntest.domain.webauthn.presentation.dto.req.RegisterVerificationRequest;
import dsm.webauthn.webauthntest.domain.webauthn.presentation.dto.res.RegisterInitResponse;
import dsm.webauthn.webauthntest.domain.webauthn.presentation.dto.res.RegisterVerificationResponse;
import dsm.webauthn.webauthntest.domain.webauthn.util.Base64UrlUtil;
import dsm.webauthn.webauthntest.domain.webauthn.util.ChallengeUtil;
import dsm.webauthn.webauthntest.global.common.redis.RedisService;
import dsm.webauthn.webauthntest.global.response.properties.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.PublicKey;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class WebAuthnService {

    private final UserRepository userRepository;
    private final RedisService redisService;
    private final PubKeyCredParamRepository pubKeyCredParamRepository;
    private final WebAuthnManager webAuthnManager;
    private final ObjectMapper objectMapper;
    private final CredentialRepository credentialRepository;

    @Value( "${webauthn.rp}")
    private String rp;
    @Value( "${webauthn.authenticatorSelection.authenticatorAttachment}")
    private String authenticatorAttachment;
    @Value("${webauthn.authenticatorSelection.requireResidentKey}")
    private boolean requireResidentKey;
    @Value("${webauthn.authenticatorSelection.userVerification}")
    private String userVerification;
    @Value("${webauthn.origin}")
    private String originStr;
    @Value("${webauthn.authenticatorSelection.userVerificationRequired}")
    private boolean userVerificationRequired;
    @Value("${webauthn.authenticatorSelection.userPresenceRequired}")
    private boolean userPresenceRequired;

    private final static String CHALLENGE_PREFIX = "challenge_";
    private final static Duration CHALLENGE_DURATION = Duration.ofMinutes(3);

    public RegisterInitResponse init(Long userId) {
        User user = findUserById(userId);
        String challenge = generateAndEncodeChallenge(userId);
        List<PubKeyCredParam> pubKeyCredParams = pubKeyCredParamRepository.findAll();

        return RegisterInitResponse.builder()
                .challenge(challenge)
                .rp(rp)
                .user(user)
                .pubKeyCredParams(pubKeyCredParams)
                .authenticatorAttachment(authenticatorAttachment)
                .requireResidentKey(requireResidentKey)
                .userVerification(userVerification)
                .build();
    }

    public RegisterVerificationResponse register(Long userId, String request) {
        log.info(request);
        RegistrationData registrationData = parseRequestToRegistrationData(request);
        log.info("registrationData:");
        RegistrationParameters registrationParameters = getRegistrationParameters(userId);
        log.info("registrationParameters:");
        verify(registrationData, registrationParameters);
        log.info("verify success");
        saveCredential(userId, registrationData);
        log.info("saveCredential success");

        return RegisterVerificationResponse.builder()
                .userId(userId)
                .build();
    }

    private void saveCredential(Long userId, RegistrationData registrationData) {
        String publicKeyJson;
        try {
//            PublicKey publicKey = registrationData.getAttestationObject().getAuthenticatorData().getAttestedCredentialData().getCOSEKey().getPublicKey();
//
//            Map<String, String> keyDetails = new HashMap<>();
//            keyDetails.put("algorithm", publicKey.getAlgorithm());
//            keyDetails.put("format", publicKey.getFormat());
//            keyDetails.put("key", Base64.getEncoder().encodeToString(publicKey.getEncoded()));
            publicKeyJson = objectMapper.writeValueAsString(registrationData.getAttestationObject().getAuthenticatorData().getAttestedCredentialData().getCOSEKey());
        } catch (JsonProcessingException e) {
            throw new WebAuthnException(ErrorCode.PARSING_ERROR);
        }

        credentialRepository.findByUserId(userId)
                .ifPresent(credentialRepository::delete);

        Credential credential = Credential.builder()
                .publicKey(publicKeyJson)
                .userId(userId)
                .credentialId(Base64UrlUtil.toBase64Url(registrationData.getAttestationObject().getAuthenticatorData().getAttestedCredentialData().getCredentialId()))
                .build();
        credentialRepository.save(credential);
    }

    private void verify(RegistrationData registrationData, RegistrationParameters registrationParameters) {
        try {
            log.info(registrationData.getAttestationObject().toString());
            webAuthnManager.verify(registrationData, registrationParameters);
        } catch (VerificationException e) {
            log.error("Verification failed: {}", e.getMessage());
            log.debug("Challenge: {}", registrationParameters.getServerProperty().getChallenge());
            log.debug("RP ID: {}", registrationParameters.getServerProperty().getRpId());
            log.debug("ClientDataJSON: {}", new String(Base64.getUrlDecoder().decode(registrationData.getCollectedClientDataBytes())));
            log.debug("AuthenticatorData: {}", registrationData.getAttestationObject().getAuthenticatorData());
            // If you would like to handle WebAuthn data verification error, please catch VerificationException
            throw new WebAuthnException(ErrorCode.VERIFICATION_ERROR);
        }
    }

    private RegistrationParameters getRegistrationParameters(Long userId) {
        ServerProperty serverProperty = getServerProperty(userId);
        log.info("serverProperty: " + serverProperty.getChallenge().getValue());

// expectations
        List<PublicKeyCredentialParameters> pubKeyCredParams = getPubKeyCredParams();
        log.info("pubKeyCredParams: " + pubKeyCredParams);
        RegistrationParameters registrationParameters = new RegistrationParameters(serverProperty, pubKeyCredParams, userVerificationRequired, userPresenceRequired);
        log.info("registrationParameters: " + registrationParameters);
        return registrationParameters;
    }

    private List<PublicKeyCredentialParameters> getPubKeyCredParams() {
        List<PubKeyCredParam> pubKeyCredParams = pubKeyCredParamRepository.findAll();

        // PubKeyCredParam -> PublicKeyCredentialParameters 변환
        return pubKeyCredParams.stream()
                .map(param -> new PublicKeyCredentialParameters(
                        PublicKeyCredentialType.create(param.getType()),
                        COSEAlgorithmIdentifier.create(param.getAlg().longValue())
                ))
                .toList();
    }

    private ServerProperty getServerProperty(Long userId) {
        Origin origin = Origin.create(originStr) /* set origin */;
        String rpId = rp /* set rpId */;
        String challengeStr = redisService.getStrValue(CHALLENGE_PREFIX + userId);
        Challenge challenge = new DefaultChallenge(challengeStr); /* set challenge */
        ;
        byte[] tokenBindingId = null /* set tokenBindingId */;
        ServerProperty serverProperty = new ServerProperty(origin, rpId, challenge, tokenBindingId);
        return serverProperty;
    }

    private RegistrationData parseRequestToRegistrationData(String request) {
        RegistrationData registrationData;
        try {
            registrationData = webAuthnManager.parseRegistrationResponseJSON(request);
            log.info("challenge: " + Base64UrlUtil.toBase64Url(registrationData.getCollectedClientData().getChallenge().getValue()));
        } catch (DataConversionException e) {
            // If you would like to handle WebAuthn data structure parse error, please catch DataConversionException
            throw new WebAuthnException(ErrorCode.PARSING_ERROR);
        }
        return registrationData;
    }

    private String generateAndEncodeChallenge(Long userId) {
        byte[] challenge = ChallengeUtil.generateChallenge();
        String encodedChallenge = Base64UrlUtil.toBase64Url(challenge);
        redisService.setValue(CHALLENGE_PREFIX + userId, encodedChallenge, CHALLENGE_DURATION);
        return encodedChallenge;
    }

    private User findUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new WebAuthnException(ErrorCode.USER_NOT_FOUND));
    }
}
