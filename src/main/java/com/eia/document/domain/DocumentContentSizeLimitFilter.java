package com.eia.document.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Rejects a content upload above {@link DocumentContentPolicy#MAX_CONTENT_BYTES}
 * before Spring materialises the body.
 *
 * <p>Servlet containers do not apply a general request-body limit to raw
 * payloads, so the proxy enforces its own. A declared {@code Content-Length}
 * is checked up front; a chunked upload with no declared length is read up to
 * the limit plus one byte and then rejected. The rejection is an
 * {@code application/problem+json} 413, the same shape the expert returns.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class DocumentContentSizeLimitFilter extends OncePerRequestFilter {
    private static final int MAX_PLUS_ONE = (int) DocumentContentPolicy.MAX_CONTENT_BYTES + 1;
    private static final Pattern CONTENT_UPLOAD =
            Pattern.compile("^/api/v1/documents/[^/]+/content$");

    private final ObjectMapper objectMapper;

    public DocumentContentSizeLimitFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"PUT".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return !CONTENT_UPLOAD.matcher(path).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws IOException, ServletException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > DocumentContentPolicy.MAX_CONTENT_BYTES) {
            writePayloadTooLarge(response);
            return;
        }
        if (declaredLength >= 0) {
            chain.doFilter(request, response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_PLUS_ONE);
        if (body.length > DocumentContentPolicy.MAX_CONTENT_BYTES) {
            writePayloadTooLarge(response);
            return;
        }
        chain.doFilter(new BufferedBodyRequest(request, body), response);
    }

    private void writePayloadTooLarge(HttpServletResponse response) throws IOException {
        ObjectNode problem = objectMapper.createObjectNode();
        problem.put("type", "about:blank");
        problem.put("title", "Payload Too Large");
        problem.put("status", HttpStatus.PAYLOAD_TOO_LARGE.value());
        problem.put("detail", DocumentContentPolicy.tooLargeDetail());
        problem.put("code", DocumentContentPolicy.FILE_TOO_LARGE);
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    /**
     * Replays an already-read chunked body downstream with the same content
     * type and character encoding.
     */
    private static final class BufferedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private ServletInputStream inputStream;
        private BufferedReader reader;

        private BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            if (inputStream == null) {
                inputStream = new BufferedServletInputStream(body);
            }
            return inputStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (reader == null) {
                String encoding = getCharacterEncoding();
                Charset charset = encoding == null || encoding.isBlank()
                        ? StandardCharsets.UTF_8 : Charset.forName(encoding);
                reader = new BufferedReader(new InputStreamReader(
                        new ByteArrayInputStream(body), charset));
            }
            return reader;
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }

    private static final class BufferedServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream delegate;

        private BufferedServletInputStream(byte[] body) {
            this.delegate = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            return delegate.read(target, offset, length);
        }

        @Override
        public boolean isFinished() {
            return delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
            throw new UnsupportedOperationException("Asynchronous reads are not supported");
        }
    }
}
