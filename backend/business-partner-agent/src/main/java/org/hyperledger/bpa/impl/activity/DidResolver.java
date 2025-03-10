/*
 * Copyright (c) 2020-2021 - for information on the respective copyright owner
 * see the NOTICE file and/or the repository at
 * https://github.com/hyperledger-labs/business-partner-agent
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hyperledger.bpa.impl.activity;

import io.micronaut.scheduling.annotation.Async;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.hyperledger.aries.api.exception.AriesException;
import org.hyperledger.aries.api.resolver.DIDDocument;
import org.hyperledger.bpa.api.CredentialType;
import org.hyperledger.bpa.api.PartnerAPI;
import org.hyperledger.bpa.api.exception.PartnerException;
import org.hyperledger.bpa.client.DidDocClient;
import org.hyperledger.bpa.core.RegisteredWebhook;
import org.hyperledger.bpa.impl.WebhookService;
import org.hyperledger.bpa.impl.util.AriesStringUtil;
import org.hyperledger.bpa.impl.util.Converter;
import org.hyperledger.bpa.model.Partner;
import org.hyperledger.bpa.model.PartnerProof;
import org.hyperledger.bpa.repository.PartnerRepository;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Optional;

import static org.hyperledger.bpa.api.CredentialType.ORGANIZATIONAL_PROFILE_CREDENTIAL;

/**
 * Special usecase to resolve a partners public did and profile in case of an
 * incoming connection where the partners public did is not known.
 */
@Slf4j
@Singleton
public class DidResolver {

    @Inject
    PartnerRepository partnerRepo;

    @Inject
    PartnerLookup partnerLookup;

    @Inject
    DidDocClient ur;

    @Inject
    Converter converter;

    @Inject
    WebhookService webhook;

    /**
     * Tries to resolve the partners public profile based on the did contained
     * within a commercial register credential.
     * 
     * @param pp {@link PartnerProof}
     */
    @Async
    public void resolveDid(PartnerProof pp) {
        try {
            if (StringUtils.isNotEmpty(pp.getSchemaId())
                    && AriesStringUtil.schemaGetName(pp.getSchemaId()).equals("commercialregister")) {
                partnerRepo.findById(pp.getPartnerId()).ifPresent(p -> {
                    if (p.getVerifiablePresentation() == null
                            && p.getIncoming() != null
                            && p.getIncoming()) {
                        Optional<DIDDocument> didDocument = Optional.empty();
                        try {
                            // check if not a public did
                            didDocument = ur.getDidDocument(p.getDid());
                        } catch (PartnerException e) {
                            log.error("{}", e.getMessage());
                        }
                        if (didDocument.isEmpty() && pp.getProof() != null) {
                            Object pubDid = pp.getProof().get("did");
                            if (pubDid != null) {
                                log.debug("Resolved did: {}", pubDid);
                                final PartnerAPI pAPI = partnerLookup.lookupPartner(pubDid.toString());
                                p.setDid(pubDid.toString());
                                p.setValid(pAPI.getValid());
                                p.setVerifiablePresentation(converter.toMap(pAPI.getVerifiablePresentation()));
                                p.setLabel("");
                                partnerRepo.update(p);
                            }
                        }
                    }
                });
            }
        } catch (Exception e) {
            log.error("Could not lookup public did.", e);
        }
    }

    /**
     * Tries to resolve the partners public profile in two steps. First try is the
     * did can be resolved to a profile, if this is not possible try to do this
     * based on a did that is embedded in the partners label. The label is supposed
     * to adhere to the following format: did:sov:xxx:123:MyLabel
     * 
     * @param p {@link Partner}
     */
    @Async
    public void lookupIncoming(Partner p) {
        lookupPartnerSave(p.getDid()).ifPresentOrElse(pAPI -> {
            partnerRepo.updateVerifiablePresentation(
                    p.getId(),
                    converter.toMap(pAPI.getVerifiablePresentation()),
                    pAPI.getValid());
            webhook.convertAndSend(RegisteredWebhook.WebhookEventType.PARTNER_ADD, pAPI);
        }, () -> {
            ConnectionLabel cl = splitDidFrom(p.getLabel());
            cl.getDid().ifPresent(did -> {
                final PartnerAPI pAPI = partnerLookup.lookupPartner(did);
                partnerRepo.updateVerifiablePresentation(
                        p.getId(),
                        converter.toMap(pAPI.getVerifiablePresentation()),
                        pAPI.getValid(),
                        cl.getLabel(),
                        did);
                //todo check the code
                Optional<PartnerAPI.PartnerCredential> publicProfileCred = pAPI.getCredential().stream()
                        .filter(cred -> cred.getType() == ORGANIZATIONAL_PROFILE_CREDENTIAL)
                        .findFirst();
                publicProfileCred.ifPresent(cred -> {
                    p.setLabel(cred.getCredentialData().get("legalName").asText(p.getLabel()));
                    partnerRepo.update(p);
                });
                webhook.convertAndSend(RegisteredWebhook.WebhookEventType.PARTNER_ADD, pAPI);
            });
        });
    }

    private Optional<PartnerAPI> lookupPartnerSave(String did) {
        try {
            if (StringUtils.isNotEmpty(did)) {
                PartnerAPI partnerAPI = partnerLookup.lookupPartner(did);
                if (partnerAPI.getVerifiablePresentation() != null) {
                    return Optional.of(partnerAPI);
                }
            }
        } catch (PartnerException | AriesException e) {
            log.debug("Did: {} could not be resolved", did);
        }
        return Optional.empty();
    }

    /**
     * Extracts the did and label components from a label is supposed to adhere to
     * the following format: did:sov:123:MyLabel.
     * 
     * @param label the label
     * @return {@link ConnectionLabel}
     */
    public static ConnectionLabel splitDidFrom(String label) {
        ConnectionLabel.ConnectionLabelBuilder cl = ConnectionLabel.builder();
        if (StringUtils.isNotEmpty(label)) {
            String[] parts = label.split(":");
            if (parts.length == 4) {
                cl.label = parts[3];
                String did = StringUtils.joinWith(":", parts[0], parts[1], parts[2]);
                cl.did(Optional.of(did));
            } else {
                cl.label = label;
                cl.did(Optional.empty());
            }
        }
        return cl.build();
    }

    @Getter
    @Builder
    public static final class ConnectionLabel {
        private final String label;
        @Builder.Default
        private final Optional<String> did = Optional.empty();
    }
}
