package com.openpms.itsm;

import com.openpms.common.ApiException;
import com.openpms.core.Current;
import com.openpms.core.Ctx;
import com.openpms.core.Snapshot;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{p}/service-desk/catalog")
public class ServiceCatalogController {
    private final Current current;
    private final ServiceCatalog catalog;

    public ServiceCatalogController(Current current, ServiceCatalog catalog) {
        this.current = current;
        this.catalog = catalog;
    }

    @GetMapping
    public ServiceCatalog.Catalog get(@PathVariable String p) {
        var ctx = current.ctx();
        return catalog.catalog(visible(ctx, p).id());
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ServiceCatalog.Category createCategory(@PathVariable String p, @RequestBody Map<String, Object> in) {
        return catalog.saveCategory(manageable(current.ctx(), p).id(), null, in);
    }

    @PatchMapping("/categories/{id}")
    @Transactional
    public ServiceCatalog.Category updateCategory(@PathVariable String p, @PathVariable UUID id, @RequestBody Map<String, Object> in) {
        return catalog.saveCategory(manageable(current.ctx(), p).id(), id, in);
    }

    @DeleteMapping("/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deleteCategory(@PathVariable String p, @PathVariable UUID id) {
        catalog.deleteCategory(manageable(current.ctx(), p).id(), id);
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ServiceCatalog.Item createItem(@PathVariable String p, @RequestBody Map<String, Object> in) {
        var ctx = current.ctx();
        return catalog.saveItem(ctx.s(), manageable(ctx, p), null, in);
    }

    @PatchMapping("/items/{id}")
    @Transactional
    public ServiceCatalog.Item updateItem(@PathVariable String p, @PathVariable UUID id, @RequestBody Map<String, Object> in) {
        var ctx = current.ctx();
        return catalog.saveItem(ctx.s(), manageable(ctx, p), id, in);
    }

    @DeleteMapping("/items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deleteItem(@PathVariable String p, @PathVariable UUID id) {
        catalog.deleteItem(manageable(current.ctx(), p).id(), id);
    }

    private Snapshot.ProjectRow visible(Ctx ctx, String p) {
        var project = ctx.findProject(p);
        if (!ctx.can(project, "service.view")) throw ApiException.notFound("프로젝트를 찾을 수 없습니다.");
        return project;
    }

    private Snapshot.ProjectRow manageable(Ctx ctx, String p) {
        var project = visible(ctx, p);
        ctx.require(project, "service.manage", "카탈로그를 고칠 권한이 없습니다.");
        return project;
    }
}
