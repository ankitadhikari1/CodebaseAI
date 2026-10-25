
package devPilot.backend.exceptions;

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(NotFoundException.class)
	ResponseEntity<Map<String, Object>> handleNotFound(NotFoundException ex, WebRequest request) {
		return error(HttpStatus.NOT_FOUND, ex.getMessage(), request);
	}

	@ExceptionHandler(BadRequestException.class)
	ResponseEntity<Map<String, Object>> handleBadRequest(BadRequestException ex, WebRequest request) {
		return error(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
	}

	@ExceptionHandler(UnauthorizedException.class)
	ResponseEntity<Map<String, Object>> handleUnauthorized(UnauthorizedException ex, WebRequest request) {
		return error(HttpStatus.UNAUTHORIZED, ex.getMessage(), request);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex, WebRequest request) {

		String message = ex.getBindingResult().getFieldErrors().stream().findFirst()
				.map(err -> err.getField() + ": " + err.getDefaultMessage()).orElse("Validation failed");

		return error(HttpStatus.BAD_REQUEST, message, request);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<?> handleGeneric(Exception ex, WebRequest request) {

		if (expectsEventStream(request)) {
			log.error("Error processing an SSE request", ex);

			// Do not return a JSON Map with a text/event-stream content type.
			// Once streaming has started, an HTTP error response may no
			// longer be possible. The streaming handler must handle errors
			// that occur asynchronously.
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.TEXT_PLAIN)
					.body("Unable to process streaming request");
		}

		log.error("Unhandled application exception", ex);

		String message = ex.getMessage() != null ? ex.getMessage() : "Unexpected error";

		return error(HttpStatus.INTERNAL_SERVER_ERROR, message, request);
	}

	private boolean expectsEventStream(WebRequest request) {
		if (!(request instanceof ServletWebRequest servletRequest)) {
			return false;
		}

		String accept = servletRequest.getRequest().getHeader("Accept");

		String path = servletRequest.getRequest().getRequestURI();

		return path != null && path.matches(".*/api/chat/sessions/[^/]+/messages/?") && accept != null
				&& accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE);
	}

	private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message, WebRequest request) {

		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("status", status.value(), "error", status.getReasonPhrase(), "message", message,
						"timestamp", Instant.now().toString()));
	}
}
