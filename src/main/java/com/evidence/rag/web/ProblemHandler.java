package com.evidence.rag.web;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.vo.ProblemResponse;
import jakarta.servlet.http.HttpServletRequest;
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
  @ExceptionHandler(ApplicationException.class)
  public ResponseEntity<ProblemResponse> problem(
      ApplicationException problem, HttpServletRequest request) {
    int status = HttpProblemMapper.status(problem);
    String title =
        switch (status) {
          case 401 -> "身份验证失败";
          case 404 -> "资料不可用";
          case 409 -> "操作冲突";
          case 422 -> "请求无效";
          case 501 -> "功能迁移中";
          default -> "请求未完成";
        };
    var body =
        new ProblemResponse(
            "https://evidence.local/problems/" + problem.code().replace('_', '-'),
            title,
            status,
            problem.getMessage(),
            request.getRequestURI(),
            problem.code());
    var result =
        ResponseEntity.status(HttpProblemMapper.status(problem))
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store");
    if (status == 401) {
      result.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    }
    return result.body(body);
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class
  })
  public ResponseEntity<ProblemResponse> invalid(Exception ignored, HttpServletRequest request) {
    return problem(
        new ApplicationException(
            FailureKind.INVALID_INPUT, "invalid_request", "请求字段、类型或 JSON 格式无效。"),
        request);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ProblemResponse> notFound(Exception ignored, HttpServletRequest request) {
    return problem(
        new ApplicationException(FailureKind.NOT_FOUND, "not_found", "资源不存在或尚未迁移。"), request);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ProblemResponse> method(Exception ignored, HttpServletRequest request) {
    return problem(
        new ApplicationException(
            FailureKind.METHOD_NOT_ALLOWED, "method_not_allowed", "此资源不支持该操作。"),
        request);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ProblemResponse> media(Exception ignored, HttpServletRequest request) {
    return problem(
        new ApplicationException(
            FailureKind.UNSUPPORTED_MEDIA, "unsupported_media_type", "请求必须使用受支持的媒体类型。"),
        request);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemResponse> unexpected(Exception ignored, HttpServletRequest request) {
    // No exception message/stack is logged: a provider, parser or JDBC exception may contain source
    // data.
    return problem(
        new ApplicationException(FailureKind.INTERNAL, "internal_error", "请求未能完成，请凭请求编号联系维护人员。"),
        request);
  }
}
