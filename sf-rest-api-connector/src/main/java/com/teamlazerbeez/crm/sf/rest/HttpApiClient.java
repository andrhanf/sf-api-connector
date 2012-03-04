/*
 * Copyright © 2011. Team Lazer Beez (http://teamlazerbeez.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.teamlazerbeez.crm.sf.rest;

import com.teamlazerbeez.crm.sf.core.Id;
import com.teamlazerbeez.crm.sf.core.SObject;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A lightweight wrapper around HTTP that handles errors and makes requests to the SF Rest endpoint urls.
 */
@ThreadSafe
final class HttpApiClient {

    private static final String API_VERSION = "21.0";
    static final TypeReference<List<ApiErrorImpl>> API_ERRORS_TYPE = new TypeReference<List<ApiErrorImpl>>() {};
    private static final String UPLOAD_CONTENT_TYPE = "application/json";

    private static final Logger logger = LoggerFactory.getLogger(HttpApiClient.class);

    @Nonnull
    private final String host;

    @Nonnull
    private final String oauthToken;

    @Nonnull
    private final ObjectMapper objectMapper;

    @Nonnull
    private final HttpClient client;

    HttpApiClient(@Nonnull String host, @Nonnull String oauthToken, @Nonnull ObjectMapper objectMapper,
            HttpClient client) {
        this.host = host;
        this.oauthToken = oauthToken;
        this.objectMapper = objectMapper;
        this.client = client;
    }

    String describeGlobal() throws IOException {
        return executeGet("/sobjects/");
    }

    String describeSObject(String sObjectType) throws IOException {
        return executeGet("/sobjects/" + sObjectType + "/describe");
    }

    String basicSObjectInfo(String sObjectType) throws IOException {
        return executeGet("/sobjects/" + sObjectType);
    }

    String create(SObject sObject) throws IOException {
        HttpPost post = new HttpPost(getUri("/sobjects/" + sObject.getType() + "/"));
        post.setEntity(getEntityForSObjectFieldsJson(sObject));
        post.addHeader("Content-Type", UPLOAD_CONTENT_TYPE);

        return executeRequestForString(post);
    }

    void delete(String sObjectType, Id id) throws IOException {
        executeRequestForString(new HttpDelete(getUri("/sobjects/" + sObjectType + "/" + id)));
    }

    String query(String soql) throws IOException {
        return executeGet("/query", new BasicNameValuePair("q", soql));
    }

    String queryMore(RestQueryLocator queryLocator) throws IOException {
        return executeGetForUri(getUriForPath(queryLocator.getContents()));
    }

    String search(String sosl) throws IOException {
        return executeGet("/search", new BasicNameValuePair("q", sosl));
    }

    String retrieve(String sObjectType, Id id, List<String> fields) throws IOException {
        return executeGet("/sobjects/" + sObjectType + "/" + id,
                new BasicNameValuePair("fields", StringUtils.join(fields, ",")));
    }

    void update(SObject sObject) throws IOException {
        HttpPatch patch = new HttpPatch(getUri("/sobjects/" + sObject.getType() + "/" + sObject.getId()));
        patch.setEntity(getEntityForSObjectFieldsJson(sObject));
        patch.addHeader("Content-Type", UPLOAD_CONTENT_TYPE);

        executeRequestForString(patch);
    }

    /**
     * @param sObject         sObject to upsert
     * @param externalIdField field name of external id field
     *
     * @return http status code
     *
     * @throws IOException on error
     */
    int upsert(SObject sObject, String externalIdField) throws IOException {
        HttpPatch patch =
                new HttpPatch(getUri("/sobjects/" + sObject.getType() + "/" + externalIdField + "/" +
                        sObject.getField(externalIdField)));
        patch.setEntity(getEntityForSObjectFieldsJson(sObject));
        patch.addHeader("Content-Type", UPLOAD_CONTENT_TYPE);

        ProcessedResponse processedResponse = executeRequest(patch);
        return processedResponse.getHttpResponse().getStatusLine().getStatusCode();
    }

    @Nonnull
    private HttpEntity getEntityForSObjectFieldsJson(SObject sObject) throws IOException {
        return new StringEntity(getSObjectFieldsAsJson(sObject), "UTF-8");
    }

    @Nonnull
    private String getSObjectFieldsAsJson(@Nonnull SObject sObject) throws IOException {
        StringWriter writer = new StringWriter();
        JsonGenerator jsonGenerator = this.objectMapper.getJsonFactory().createJsonGenerator(writer);

        jsonGenerator.writeStartObject();

        for (Map.Entry<String, String> entry : sObject.getAllFields().entrySet()) {
            if (entry.getValue() == null) {
                jsonGenerator.writeNullField(entry.getKey());
            } else {
                jsonGenerator.writeStringField(entry.getKey(), entry.getValue());
            }
        }

        jsonGenerator.writeEndObject();
        jsonGenerator.close();

        writer.close();

        return writer.toString();
    }

    @CheckForNull
    private String executeGet(String pathFragment) throws IOException {
        return executeGetForUri(getUri(pathFragment));
    }

    @CheckForNull
    private String executeGet(String pathFragment, NameValuePair param) throws IOException {
        return executeGet(pathFragment, Arrays.asList(param));
    }

    @CheckForNull
    private String executeGet(String pathFragment, List<NameValuePair> params) throws IOException {
        return executeGetForUri(getUri(pathFragment, params));
    }

    @CheckForNull
    private String executeGetForUri(URI uri) throws IOException {
        return executeRequestForString(new HttpGet(uri));
    }

    @Nonnull
    private URI getUri(String pathFragment) throws IOException {
        return getUriForPath("/services/data/v" + API_VERSION + pathFragment);
    }

    @Nonnull
    private URI getUri(String pathFragment, List<NameValuePair> params) throws IOException {
        return getUriForPath("/services/data/v" + API_VERSION + pathFragment, params);
    }

    @Nonnull
    private URI getUriForPath(String path) throws IOException {
        try {
            return URIUtils.createURI("https", this.host, 443, path, null, null);
        } catch (URISyntaxException e) {
            throw new IOException("Couldn't create URI", e);
        }
    }

    @Nonnull
    private URI getUriForPath(String path, List<NameValuePair> params) throws IOException {
        try {
            return URIUtils.createURI("https", this.host, 443, path, URLEncodedUtils.format(params, "UTF-8"), null);
        } catch (URISyntaxException e) {
            throw new IOException("Couldn't create URI", e);
        }
    }

    @CheckForNull
    private String executeRequestForString(@Nonnull HttpUriRequest request) throws IOException {
        return executeRequest(request).getResponseBody();
    }

    @Nonnull
    private ProcessedResponse executeRequest(HttpUriRequest request) throws IOException {
        request.addHeader("Authorization", "OAuth " + this.oauthToken);
        HttpResponse response = this.client.execute(request);

        return new ProcessedResponse(response, checkResponse(request, response));
    }

    /**
     * @param request  the http request
     * @param response the http response
     *
     * @return response body. May be null.
     *
     * @throws IOException if the response indicates an error
     */
    @CheckForNull
    private String checkResponse(HttpUriRequest request, HttpResponse response) throws IOException {
        HttpEntity entity = response.getEntity();

        String responseBody;
        if (entity == null) {
            responseBody = null;
        } else {
            responseBody = EntityUtils.toString(entity);
        }

        throwApiExceptionIfInvalid(request.getURI().toString(), response, responseBody);
        return responseBody;
    }

    private void throwApiExceptionIfInvalid(@Nonnull String url, @Nonnull HttpResponse response,
            @CheckForNull String responseBody) throws IOException {
        StatusLine statusLine = response.getStatusLine();
        int statusCode = statusLine.getStatusCode();

        if (statusCode >= 200 && statusCode < 300) {
            return;
        }

        List<ApiError> errors;
        if (responseBody == null) {
            errors = Collections.emptyList();
        } else {
            try {
                errors = this.objectMapper.readValue(responseBody, API_ERRORS_TYPE);
            } catch (JsonParseException e) {
                logger.warn("Couldn't parse response <" + responseBody + ">", e);
                errors = Collections.emptyList();
            } catch (JsonMappingException e) {
                logger.warn("Couldn't parse response <" + responseBody + ">", e);
                errors = Collections.emptyList();
            }
        }

        switch (statusCode) {
            case 300:
                throw new ApiException(url, statusCode, statusLine.getReasonPhrase(), errors, responseBody,
                        "External ID already used");

            case 400:
                throw new ApiException(url, statusCode, statusLine.getReasonPhrase(), errors, responseBody,
                        "Request could not be understood");

            case 401:
                throw new ApiException(url, statusCode, statusLine.getReasonPhrase(), errors, responseBody,
                        "Invalid session ID or Oauth token");

            case 403:
                throw new ApiException(url, statusCode, statusLine.getReasonPhrase(), errors, responseBody,
                        "Request refused; check permissions");

            case 404:
                throw new ApiException(url, statusCode, statusLine.getReasonPhrase(), errors, responseBody,
                        "Resource could not be found");

            case 405:
                throw new ApiException(url, statusCode, statusLine.getReasonPhrase(), errors, responseBody,
                        "Method not allowed for specified resource");

            case 415:
                throw new ApiException(url, statusCode, statusLine.getReasonPhrase(), errors, responseBody,
                        "Request is not in a supported format for the resource and method");

            case 500:
                throw new ApiException(url, statusCode, statusLine.getReasonPhrase(), errors, responseBody,
                        "Force.com error");
            default:
                throw new ApiException(url, statusCode, statusLine.getReasonPhrase(), errors, responseBody,
                        "Unclassified error");
        }
    }

    /**
     * A trivial holder around an HttpResponse and the String body.
     */
    @NotThreadSafe
    private static class ProcessedResponse {

        @Nonnull
        private final HttpResponse httpResponse;

        @CheckForNull
        private final String responseBody;

        private ProcessedResponse(HttpResponse httpResponse, String responseBody) {
            this.httpResponse = httpResponse;
            this.responseBody = responseBody;
        }

        @Nonnull
        public HttpResponse getHttpResponse() {
            return httpResponse;
        }

        @CheckForNull
        public String getResponseBody() {
            return responseBody;
        }
    }
}