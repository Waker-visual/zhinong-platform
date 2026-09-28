package app.zhinong.api;

import app.zhinong.business.Records;
import app.zhinong.business.TenantService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class TenantController {

  private final TenantService tenants;

  public TenantController(TenantService tenants) {
    this.tenants = tenants;
  }

  @GetMapping("/platform/tenants")
  Object tenants() {
    return tenants.list();
  }

  @PostMapping("/platform/tenants")
  Object create(@Valid @RequestBody Records.Tenant input) {
    return tenants.create(input);
  }

  @PatchMapping("/platform/tenants/{id}")
  Object enabled(@PathVariable String id, @RequestBody Records.Enabled input) {
    tenants.enabled(id, input.enabled());
    return Map.of("ok", true);
  }

  @GetMapping("/members")
  Object members() {
    return tenants.members();
  }

  @PostMapping("/members")
  Object member(@Valid @RequestBody Records.Member input) {
    return tenants.member(input);
  }

  @PatchMapping("/members/{id}")
  Object memberEnabled(@PathVariable String id, @RequestBody Records.Enabled input) {
    tenants.memberEnabled(id, input.enabled());
    return Map.of("ok", true);
  }
}
