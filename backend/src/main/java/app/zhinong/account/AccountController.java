package app.zhinong.account;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AccountController {

  private final AccountService accounts;

  public AccountController(AccountService accounts) {
    this.accounts = accounts;
  }

  public record Profile(
    @NotBlank @Size(max = 80) String displayName,
    @Size(max = 400000) String avatarData
  ) {}

  public record Appearance(
    @NotNull @Pattern(regexp = "LIGHT|DARK|SYSTEM") String themeMode,
    @NotNull @Pattern(regexp = "FOREST|BLUE|AMBER|ROSE") String accent
  ) {}

  public record Password(
    @NotBlank @Size(max = 72) String currentPassword,
    @NotBlank @Size(min = 12, max = 72) String newPassword
  ) {}

  @GetMapping("/account")
  Object current() {
    return accounts.current();
  }

  @PutMapping("/account/profile")
  Object profile(@Valid @RequestBody Profile input) {
    return accounts.profile(input);
  }

  @PutMapping("/account/appearance")
  Object appearance(@Valid @RequestBody Appearance input) {
    return accounts.appearance(input);
  }

  @PostMapping("/account/password")
  Object password(@Valid @RequestBody Password input) {
    accounts.changePassword(input);
    return Map.of(
      "ok",
      true,
      "message",
      "密码已修改，所有登录已退出，请重新登录"
    );
  }

  @PostMapping("/members/{id}/reset-password")
  Object reset(@PathVariable String id, @Valid @RequestBody Password input) {
    accounts.resetPassword(id, input);
    return Map.of("ok", true, "mustChangePassword", true);
  }
}
