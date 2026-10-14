package com.openpms.itsm;

import static com.openpms.db.Tables.USER_GROUPS;

import com.openpms.api.Dto;
import com.openpms.common.ApiException;
import com.openpms.core.Current;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ServiceDeskConfigController {
    private final Current current;
    private final ServiceDeskConfig config;
    private final DSLContext db;

    public ServiceDeskConfigController(Current current, ServiceDeskConfig config, DSLContext db) {
        this.current = current;
        this.config = config;
        this.db = db;
    }

    @GetMapping("/service-calendars")
    public List<ServiceDeskConfig.CalendarDto> calendars() {
        current.ctx();
        return config.calendars();
    }

    @PostMapping("/service-calendars")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ServiceDeskConfig.CalendarDto create(@RequestBody Map<String, Object> in) {
        admin();
        return config.saveCalendar(null, in);
    }

    @PatchMapping("/service-calendars/{id}")
    @Transactional
    public ServiceDeskConfig.CalendarDto update(@PathVariable UUID id, @RequestBody Map<String, Object> in) {
        admin();
        return config.saveCalendar(id, in);
    }

    @DeleteMapping("/service-calendars/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable UUID id) {
        admin();
        config.deleteCalendar(id);
    }

    @GetMapping("/projects/{p}/service-desk/settings")
    public ServiceDeskConfig.Settings settings(@PathVariable String p) {
        var ctx = current.ctx();
        var project = ctx.findProject(p);
        if (!ctx.can(project, "service.view")) throw ApiException.notFound("프로젝트를 찾을 수 없습니다.");
        return config.settings(project.id());
    }

    @PutMapping("/projects/{p}/service-desk/settings")
    @Transactional
    public ServiceDeskConfig.Settings replace(@PathVariable String p, @RequestBody Map<String, Object> in) {
        var ctx = current.ctx();
        var project = ctx.findProject(p);
        if (!ctx.can(project, "service.view")) throw ApiException.notFound("프로젝트를 찾을 수 없습니다.");
        ctx.require(project, "service.manage", "서비스 데스크 설정을 바꿀 권한이 없습니다.");
        return config.replace(ctx.s(), project.id(), in);
    }

    /** Groups an agent can route a ticket to (all user groups; names only). */
    @GetMapping("/projects/{p}/service-desk/groups")
    public List<Dto.IdName> groups(@PathVariable String p) {
        var ctx = current.ctx();
        var project = ctx.findProject(p);
        if (!ctx.can(project, "service.view")) throw ApiException.notFound("프로젝트를 찾을 수 없습니다.");
        return db.select(USER_GROUPS.ID, USER_GROUPS.NAME).from(USER_GROUPS).orderBy(USER_GROUPS.NAME).fetch(r -> new Dto.IdName(r.value1(), r.value2()));
    }

    private void admin() {
        if (!current.ctx().isAdmin()) throw ApiException.forbidden("관리자만 서비스 달력을 고칠 수 있습니다.");
    }
}
