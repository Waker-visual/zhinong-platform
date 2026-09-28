package app.zhinong.api;

import app.zhinong.security.AuthService;
import app.zhinong.security.Identity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private final AuthService auth;

  public AuthController(AuthService auth) {
    this.auth = auth;
  }

  public record Login(
    @NotBlank @Size(max = 40) String tenantCode,
    @NotBlank @Size(max = 60) String username,
    @NotBlank @Size(max = 72) String password
  ) {}

  @PostMapping("/login")
  Map<String, Object> login(@Valid @RequestBody Login request) {
    return auth.login(request.tenantCode(), request.username(), request.password());
  }

  @GetMapping("/me")
  Identity me() {
    return Identity.current();
  }

  @PostMapping("/logout")
  Map<String, Boolean> logout(@RequestHeader("Authorization") String header) {
    auth.logout(header.substring(7));
    return Map.of("ok", true);
  }
}
