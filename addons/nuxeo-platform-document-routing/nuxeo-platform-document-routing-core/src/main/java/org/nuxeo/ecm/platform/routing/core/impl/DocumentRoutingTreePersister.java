/*
 * (C) Copyright 2009 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     arussel
 */
package org.nuxeo.ecm.platform.routing.core.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.ClientRuntimeException;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.security.ACE;
import org.nuxeo.ecm.core.api.security.ACL;
import org.nuxeo.ecm.core.api.security.ACP;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.platform.routing.api.DocumentRoute;
import org.nuxeo.ecm.platform.routing.api.DocumentRoutingConstants;
import org.nuxeo.ecm.platform.routing.core.api.DocumentRoutingPersister;
import org.nuxeo.ecm.platform.routing.core.persistence.TreeHelper;
import org.nuxeo.ecm.platform.usermanager.UserManager;
import org.nuxeo.runtime.api.Framework;

/**
 * The default persister. It persists the {@link DocumentRoute} in a tree
 * hierarchy ressembling the current date.
 *
 * @author arussel
 *
 */
public class DocumentRoutingTreePersister implements DocumentRoutingPersister {

    private static final String DC_TITLE = "dc:title";

    protected static final Log log = LogFactory.getLog(DocumentRoutingTreePersister.class);

    @Override
    public DocumentModel getParentFolderForDocumentRouteInstance(
            DocumentModel document, CoreSession session) {
        try {
            return TreeHelper.getOrCreateDateTreeFolder(session,
                    getOrCreateRootOfDocumentRouteInstanceStructure(session),
                    new Date(), "Folder");
        } catch (ClientException e) {
            throw new ClientRuntimeException(e);
        }
    }

    @Override
    public DocumentModel createDocumentRouteInstanceFromDocumentRouteModel(
            DocumentModel model, CoreSession session) {
        DocumentModel parent = getParentFolderForDocumentRouteInstance(model,
                session);
        DocumentModel result = null;
        try {
            result = session.copy(model.getRef(), parent.getRef(), null);
            // using the ref, the value of the attached document might not been
            // saved on the model
            result.setPropertyValue(
                    DocumentRoutingConstants.ATTACHED_DOCUMENTS_PROPERTY_NAME,
                    model.getPropertyValue(DocumentRoutingConstants.ATTACHED_DOCUMENTS_PROPERTY_NAME));
            session.saveDocument(result);
            session.save();
        } catch (ClientException e) {
            throw new ClientRuntimeException(e);
        }
        return result;
    }

    @Override
    public DocumentModel saveDocumentRouteInstanceAsNewModel(
            DocumentModel routeInstance, DocumentModel parentFolder,
            CoreSession session) {
        try {
            return session.copy(routeInstance.getRef(), parentFolder.getRef(),
                    null);
        } catch (ClientException e) {
            throw new ClientRuntimeException(e);
        }
    }

    @Override
    public DocumentModel getOrCreateRootOfDocumentRouteInstanceStructure(
            CoreSession session) {
        DocumentModel root;
        try {
            root = getDocumentRouteInstancesStructure(session);
            if (root == null) {
                root = createDocumentRouteInstancesStructure(session);
            }
            return root;
        } catch (ClientException e) {
            throw new ClientRuntimeException(e);
        }
    }

    protected DocumentModel createDocumentRouteInstancesStructure(
            CoreSession session) throws ClientException {
        DocumentModel defaultDomain = session.getChildren(
                session.getRootDocument().getRef()).get(0);
        DocumentModel root = session.createDocumentModel(
                defaultDomain.getPathAsString(),
                DocumentRoutingConstants.DOCUMENT_ROUTE_INSTANCES_ROOT_ID,
                DocumentRoutingConstants.DOCUMENT_ROUTE_INSTANCES_ROOT_DOCUMENT_TYPE);
        root.setPropertyValue(
                DC_TITLE,
                DocumentRoutingConstants.DOCUMENT_ROUTE_INSTANCES_ROOT_DOCUMENT_TYPE);
        root = session.createDocument(root);
        ACP acp = session.getACP(root.getRef());
        ACL acl = acp.getOrCreateACL(ACL.LOCAL_ACL);
        acl.addAll(getACEs());
        session.setACP(root.getRef(), acp, true);
        session.save();
        return root;
    }

    /**
     * @return
     */
    protected List<ACE> getACEs() {
        List<ACE> aces = new ArrayList<ACE>();
        for (String group : getUserManager().getAdministratorsGroups()) {
            aces.add(new ACE(group, SecurityConstants.EVERYTHING, true));
        }
        aces.add(new ACE(DocumentRoutingConstants.ROUTE_MANAGERS_GROUP_NAME,
                SecurityConstants.READ_WRITE, true));
        aces.add(new ACE(SecurityConstants.EVERYONE,
                SecurityConstants.EVERYTHING, false));
        return aces;
    }

    protected UserManager getUserManager() {
        try {
            return Framework.getService(UserManager.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected DocumentModel getDocumentRouteInstancesStructure(
            CoreSession session) throws ClientException {
        DocumentModelList res = session.query(String.format(
                "SELECT * from %s",
                DocumentRoutingConstants.DOCUMENT_ROUTE_INSTANCES_ROOT_DOCUMENT_TYPE));
        if (res == null || res.isEmpty()) {
            return null;
        }
        if (res.size() > 1) {
            if (log.isWarnEnabled()) {
                log.warn("More han one DocumentRouteInstanceRoot found:");
                for (DocumentModel model : res) {
                    log.warn(" - " + model.getName() + ", "
                            + model.getPathAsString());
                }
            }
        }
        return res.get(0);
    }
}