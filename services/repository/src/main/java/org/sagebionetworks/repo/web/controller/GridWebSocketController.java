package org.sagebionetworks.repo.web.controller;

import static org.sagebionetworks.repo.model.oauth.OAuthScope.modify;
import static org.sagebionetworks.repo.model.oauth.OAuthScope.view;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.StreamSupport;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.collections.IteratorUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.JSONObject;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.web.RequiredScope;
import org.sagebionetworks.repo.web.UrlHelpers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The set of REST calls that are made by AWS API Gateway WebSockets to support
 * bidirectional grid communication.
 * </p>
 * The ControllerInfo annotation is excluded since this is an internal API.*
 */
@Controller
@RequestMapping(UrlHelpers.REPO_PATH)
public class GridWebSocketController {

	private static final Log log = LogFactory.getLog(GridWebSocketController.class);

	/**
	 * 
	 * @param userId
	 * @param gridSessionId
	 * @param replicaId
	 * @param request
	 * @return
	 */
	@RequiredScope({ view, modify })
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.GRID_WS_CONNECT, method = RequestMethod.POST)
	public @ResponseBody String connect(@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@RequestParam String gridSessionId, @RequestParam String replicaId, HttpServletRequest request) {
		log(userId, gridSessionId, replicaId, request);

		return "hello";
	}

	@RequiredScope({ view, modify })
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.GRID_WS_DISCONNECT, method = RequestMethod.POST)
	public @ResponseBody String disconnect(@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@RequestParam (required = false) String gridSessionId, @RequestParam (required = false) String replicaId, HttpServletRequest request) {
		log(userId, gridSessionId, replicaId, request);
		return "goodbye";
	}

	@RequiredScope({ view, modify })
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.GRID_WS_DEFAULT, method = RequestMethod.POST)
	public @ResponseBody String defaultMessage(@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@RequestParam (required = false) String gridSessionId, @RequestParam (required = false) String replicaId, HttpServletRequest request) {
		log(userId, gridSessionId, replicaId, request);
		return "default";
	}

	void log(Long userId, String gridSessionId, String replicaId, HttpServletRequest request) {
		JSONObject l = new JSONObject();
		l.put("userId", userId);
		l.put("gridSessionId", gridSessionId);
		l.put("replicaId", replicaId);
		l.put("headers", getHeader(request));
		l.put("requestURI", request.getRequestURI());
		l.put("method", request.getMethod());
		l.put("protocal", request.getProtocol());
		l.put("contentType", request.getContentType());
		l.put("contentLength", request.getContentLength());
		l.put("characterEncoding", request.getCharacterEncoding());
		l.put("scheme", request.getScheme());
		l.put("serverName", request.getServerName());
		l.put("parameters", getParameters(request));
		l.put("attributeNames", IteratorUtils.toList(request.getAttributeNames().asIterator()));
	    StringWriter writer = new StringWriter();
	    try {
			IOUtils.copy(request.getInputStream(), writer, StandardCharsets.UTF_8.name());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	    l.put("body", writer.toString());
		log.info(l.toString(2));
	}

	static JSONObject getHeader(HttpServletRequest request) {
		JSONObject r = new JSONObject();
		StreamSupport
				.stream(Spliterators.spliteratorUnknownSize(request.getHeaderNames().asIterator(), Spliterator.ORDERED),
						false)
				.filter(k -> !"Authorization".equals(k)).forEach(k -> {
					r.put(k, request.getHeader(k));
				});
		return r;
	}

	static JSONObject getParameters(HttpServletRequest request) {
		JSONObject r = new JSONObject();
		StreamSupport.stream(
				Spliterators.spliteratorUnknownSize(request.getParameterNames().asIterator(), Spliterator.ORDERED),
				false).forEach(k -> {
					r.put(k, request.getParameter(k));
				});
		return r;
	}
}
