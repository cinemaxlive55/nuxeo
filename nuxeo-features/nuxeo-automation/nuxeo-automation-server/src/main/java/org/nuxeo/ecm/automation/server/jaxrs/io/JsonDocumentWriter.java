/*
 * (C) Copyright 2006-2008 Nuxeo SAS (http://nuxeo.com/) and contributors.
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
 *     bstefanescu
 */
package org.nuxeo.ecm.automation.server.jaxrs.io;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.URLEncoder;
import java.util.Calendar;
import java.util.List;

import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.MessageBodyWriter;
import javax.ws.rs.ext.Provider;

import net.sf.json.JSONArray;
import net.sf.json.JSONNull;
import net.sf.json.JSONObject;

import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.model.DocumentPart;
import org.nuxeo.ecm.core.api.model.Property;
import org.nuxeo.ecm.core.api.model.impl.ArrayProperty;
import org.nuxeo.ecm.core.api.model.impl.ComplexProperty;
import org.nuxeo.ecm.core.api.model.impl.ListProperty;
import org.nuxeo.ecm.core.api.model.impl.primitives.BlobProperty;
import org.nuxeo.ecm.core.schema.utils.DateParser;
import org.nuxeo.ecm.webengine.WebException;

/**
 * @author <a href="mailto:bs@nuxeo.com">Bogdan Stefanescu</a>
 */
@Provider
@Produces( { "application/json+nxentity", "application/json" })
public class JsonDocumentWriter implements MessageBodyWriter<DocumentModel> {

    @Context
    protected HttpHeaders headers;

    public long getSize(DocumentModel arg0, Class<?> arg1, Type arg2,
            Annotation[] arg3, MediaType arg4) {
        return -1L;
    }

    public boolean isWriteable(Class<?> arg0, Type arg1, Annotation[] arg2,
            MediaType arg3) {
        return DocumentModel.class.isAssignableFrom(arg0);
    }

    public void writeTo(DocumentModel doc, Class<?> arg1, Type arg2,
            Annotation[] arg3, MediaType arg4,
            MultivaluedMap<String, Object> arg5, OutputStream arg6)
            throws IOException, WebApplicationException {
        try {
            // schema names: dublincore, file, ... or *
            List<String> props = headers.getRequestHeader("X-NXDocumentProperties");
            JSONObject obj = null;
            if (props == null || props.isEmpty()) {
                obj = getJSON(doc, null);
            } else {
                obj = getJSON(doc, props);
            }
            arg6.write(obj.toString(2).getBytes("UTF-8"));
        } catch (Exception e) {
            throw WebException.wrap(e);
        }
    }

    public static JSONObject getJSON(DocumentModel doc, List<String> schemas)
            throws Exception {
        JSONObject json = new JSONObject();
        json.element("entity-type", "document");
        json.element("uid", doc.getId());
        json.element("path", doc.getPathAsString());
        json.element("type", doc.getType());
        json.element("state", doc.getCurrentLifeCycleState());
        json.element("lock", doc.getLock());
        json.element("title", doc.getTitle());
        Calendar cal = (Calendar) doc.getPart("dublincore").getValue("modified");
        if (cal != null) {
            json.element("lastModified",
                    DateParser.formatW3CDateTime(cal.getTime()));
        }

        if (schemas == null || schemas.isEmpty()) {
            return json;
        }

        JSONObject props = new JSONObject();
        if (schemas.size() == 1 && "*".equals(schemas.get(0))) { // full
            // document
            for (String schema : doc.getDeclaredSchemas()) {
                addSchema(props, doc, schema);
            }
        } else {
            for (String schema : schemas) {
                addSchema(props, doc, schema);
            }
        }

        json.element("properties", props);
        return json;
    }

    protected static void addSchema(JSONObject json, DocumentModel doc,
            String schema) throws Exception {
        DocumentPart part = doc.getPart(schema);
        String prefix = part.getSchema().getNamespace().prefix;
        if (prefix == null || prefix.length() == 0) {
            prefix = schema;
        }
        prefix += ':';
        String filesBaseUrl = "files/" + doc.getId() + "?path=";
        for (Property p : part.getChildren()) {
            json.element(prefix + p.getField().getName().getLocalName(),
                    propertyToJsonValue(filesBaseUrl, p));
        }
    }

    /**
     * Converts the given core property to JSON format. The given filesBaseUrl
     * is the baseUrl that can be used to locate blob content and is useful to
     * generate blob urls.
     */
    protected static Object propertyToJsonValue(final String filesBaseUrl,
            Property prop) throws Exception {
        org.nuxeo.ecm.core.schema.types.Type type = prop.getType();
        if (prop.isScalar()) {
            Object v = prop.getValue();
            if (v == null) {
                return JSONNull.getInstance();
            }
            return type.encode(v);
        } else if (prop.isList()) {
            if (prop instanceof ArrayProperty) {
                Object[] ar = (Object[]) prop.getValue();
                if (ar == null) {
                    return new JSONArray();
                }
                JSONArray jsar = new JSONArray();
                for (int i = 0; i < ar.length; i++) {
                    jsar.add(type.encode(ar[i]));
                }
                return jsar;
            } else {
                ListProperty listp = (ListProperty) prop;
                JSONArray jsar = new JSONArray();
                for (Property p : listp.getChildren()) {
                    jsar.add(propertyToJsonValue(filesBaseUrl, p));
                }
                return jsar;
            }
        } else {
            if (prop.isPhantom()) {
                return JSONNull.getInstance();
            }
            if (prop instanceof BlobProperty) { // a blob
                Blob blob = (Blob) ((BlobProperty) prop).getValue();
                JSONObject jsob = new JSONObject();
                String v = blob.getFilename();
                jsob.element("name", v == null ? JSONNull.getInstance() : v);
                v = blob.getMimeType();
                jsob.element("mime-type", v == null ? JSONNull.getInstance()
                        : v);
                v = blob.getEncoding();
                jsob.element("encoding", v == null ? JSONNull.getInstance() : v);
                v = blob.getDigest();
                jsob.element("digest", v == null ? JSONNull.getInstance() : v);
                v = Long.toString(blob.getLength());
                jsob.element("length", v);
                jsob.element("data", filesBaseUrl
                        + URLEncoder.encode(prop.getPath(), "UTF-8"));
                return jsob;
            } else { // a complex property
                ComplexProperty cp = (ComplexProperty) prop;
                if (prop.isPhantom()) {
                    return JSONNull.getInstance();
                }
                JSONObject jsob = new JSONObject();
                for (Property p : cp.getChildren()) {
                    jsob.put(p.getName(), propertyToJsonValue(filesBaseUrl, p));
                }
                return jsob;
            }
        }
    }

}