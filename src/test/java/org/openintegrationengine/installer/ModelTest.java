package org.openintegrationengine.installer;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Version ordering, generated passwords and installation-plan parsing. */
class ModelTest {
    @Test void versionsCompareNumerically() {
        assertTrue(Model.compare("4.7.0", "4.6.0") > 0);
        assertTrue(Model.compare("4.10.0", "4.9.9") > 0);
        assertEquals(0, Model.compare("4.7", "4.7.0"));
        assertEquals(0, Model.compare("4.7.0-SNAPSHOT", "4.7.0"));
        assertTrue(Model.compare("4.6.0", "4.7.0") < 0, "an older payload is a downgrade");
        assertThrows(IllegalArgumentException.class, () -> Model.compare("unknown", "4.7.0"));
        assertThrows(IllegalArgumentException.class, () -> Model.compare("4.7.0", "4.x"));
    }

    @Test void generatedPasswordsAreLongUnambiguousAndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String password = Model.password();
            assertEquals(24, password.length());
            assertTrue(password.matches("[A-HJ-NP-Za-km-z2-9]+"), password);
            assertTrue(seen.add(password));
        }
    }

    @Test void portsMustBeInRange() {
        assertFalse(Model.portFree(0));
        assertFalse(Model.portFree(65536));
    }

    @Test void planDefaultsSelectEveryAddOnAndNoService() {
        Model.Plan plan = Model.Plan.from(Json.object().put("installDir", "/opt/oie").put("httpPort", "8080").put("httpsPort", 8443));
        assertEquals(Path.of("/opt/oie"), plan.home());
        assertEquals(Model.BUNDLED, plan.javaCmd());
        assertEquals(8080, plan.httpPort());
        assertEquals(8443, plan.httpsPort());
        assertEquals("derby", plan.database());
        assertFalse(plan.service());
        assertEquals(Map.of("websupport", true, "tls-manager", true, "rbac", true, "simple-channel-history", true), plan.plugins());
    }

    @Test void planAddOnChoicesAreValidated() {
        var plan = Json.object().put("installDir", "/opt/oie");
        plan.putObject("plugins").put("rbac", false);
        assertFalse(Model.Plan.from(plan).plugins().get("rbac"));
        assertTrue(Model.Plan.from(plan).plugins().get("websupport"));
        plan.putObject("plugins").put("unknown", true);
        assertThrows(IllegalArgumentException.class, () -> Model.Plan.from(plan));
        plan.putObject("plugins").put("rbac", "no");
        assertThrows(IllegalArgumentException.class, () -> Model.Plan.from(plan));
        plan.put("plugins", "all");
        assertThrows(IllegalArgumentException.class, () -> Model.Plan.from(plan));
    }

    @Test void tlsManagerCanBeSkippedAndDefaultsOnForOlderPlans() {
        var plan = Json.object().put("installDir", "/opt/oie");
        plan.putObject("plugins").put("rbac", false);
        assertTrue(Model.Plan.from(plan).plugins().get("tls-manager"));
        plan.withObject("plugins").put("tls-manager", false);
        assertFalse(Model.Plan.from(plan).plugins().get("tls-manager"));
        assertTrue(Model.Plan.from(plan).plugins().get("websupport"));
        assertEquals(List.of("websupport", "tls-manager", "rbac", "simple-channel-history"), Payload.IDS);
    }

    @Test void planServiceMustBeBoolean() {
        assertThrows(IllegalArgumentException.class, () -> Model.Plan.from(Json.object().put("installDir", "/opt/oie").put("service", "yes")));
        assertThrows(IllegalArgumentException.class, () -> Model.Plan.from(Json.MAPPER.createArrayNode()));
    }

    @Test void planPortsThatAreNotNumbersBecomeZero() {
        assertEquals(0, Model.Plan.from(Json.object().put("installDir", "/opt/oie").put("httpPort", "http")).httpPort());
    }

    @Test void homePathsExpandTheUserFolder() {
        assertEquals(Model.userHome().resolve("oie"), Model.expand("~/oie"));
        assertEquals(Model.userHome(), Model.expand(" ~ "));
        assertEquals(Path.of("/opt/oie"), Model.expand("/opt/./oie/"));
    }
}
