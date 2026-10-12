package app.zhinong.api;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class Errors {

  @ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessException.class, TransactionTimedOutException.class})
  ResponseEntity<?> temporarilyUnavailable(Exception e) {
    return ResponseEntity.status(503).header("Retry-After", "2")
      .body(Map.of("message", "数据服务暂时繁忙，请稍后重试"));
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> api(ApiException e) {
    return ResponseEntity.status(e.status()).body(Map.of("message", e.getMessage()));
  }

  @ExceptionHandler(
    { MethodArgumentNotValidException.class, HttpMessageNotReadableException.class }
  )
  ResponseEntity<?> validation(Exception e) {
    return ResponseEntity.badRequest().body(
      Map.of("message", "字段不完整、格式错误或包含不允许的字段")
    );
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> conflict(Exception e) {
    return ResponseEntity.status(409).body(Map.of("message", "记录重复或仍被其他业务引用"));
  }
}
