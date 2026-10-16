package com.openpms.cicd;

import com.openpms.common.ApiException;
import com.openpms.common.In;
import com.openpms.core.Current;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리 › CI/CD helpers that belong to no single connection. Under /api/v1/admin/cicd, so API tokens stay out. */
@RestController
@RequestMapping("/api/v1/admin/cicd")
public class CicdAdminToolsController {
    private final Current current;

    public CicdAdminToolsController(Current current) {
        this.current = current;
    }

    /** What the image tag rule makes of an image: the server answers so the screen never disagrees with the processor. */
    public record TagTest(String sha) {}

    @PostMapping("/image-tag-test")
    public TagTest imageTagTest(@RequestBody Map<String, Object> in) {
        CicdConnections.requireManager(current.ctx());
        var pattern = ImageTags.validate(In.str(in, "pattern"));
        var image = In.str(in, "image");
        if (image != null && image.length() > 500) throw ApiException.field("image", "이미지 이름이 너무 깁니다.");
        return new TagTest(ImageTags.sha(image, pattern).orElse(null));
    }
}
