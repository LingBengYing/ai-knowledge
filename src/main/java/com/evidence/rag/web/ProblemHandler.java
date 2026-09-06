package com.evidence.rag.web;

import com.evidence.rag.shared.Problem;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ProblemHandler {
  @ExceptionHandler(Problem.class)
  public ResponseEntity<Map<String, Object>> problem(Problem problem, HttpServletRequest request) {
    var body = new LinkedHashMap<String, Object>();
    body.put("type", "https://evidence.local/problems/" + problem.code().replace('_', '-'));
    body.put(
        "title",
        switch (problem.status()) {
          case 401 -> "身份验证失败";
          case 404 -> "资料不可用";
          case 409 -> "操作冲突";
          case 422 -> "请求无效";
          case 501 -> "功能迁移中";
          default -> "请求未完成";
        });
    body.put("status", problem.status());
    body.put("detail", problem.getMessage());
    body.put("instance", request.getRequestURI());
    body.put("error_code", problem.code());
    var result =
        ResponseEntity.status(problem.status())
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store");
    if (problem.status() == 401) result.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    return result.body(body);
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class
  })
  public ResponseEntity<Map<String, Object>> invalid(
      Exception ignored, HttpServletRequest request) {
    return problem(new Problem(422, "invalid_request", "请求字段、类型或 JSON 格式无效。"), request);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<Map<String, Object>> notFound(
      Exception ignored, HttpServletRequest request) {
    return problem(new Problem(404, "not_found", "资源不存在或尚未迁移。"), request);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<Map<String, Object>> method(Exception ignored, HttpServletRequest request) {
    return problem(new Problem(405, "method_not_allowed", "此资源不支持该操作。"), request);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<Map<String, Object>> media(Exception ignored, HttpServletRequest request) {
    return problem(new Problem(415, "unsupported_media_type", "请求必须使用受支持的媒体类型。"), request);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> unexpected(
      Exception ignored, HttpServletRequest request) {
    // No exception message/stack is logged: a provider, parser or JDBC exception may contain source
    // data.
    return problem(new Problem(500, "internal_error", "请求未能完成，请凭请求编号联系维护人员。"), request);
  }
}
