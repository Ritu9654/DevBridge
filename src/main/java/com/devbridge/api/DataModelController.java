package com.devbridge.api;

import com.devbridge.datamodel.DataModel;
import com.devbridge.datamodel.DataModelService;
import com.devbridge.datamodel.DataModelSummary;
import com.devbridge.profile.ProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/profiles/{profileId}/data-model")
public class DataModelController {

    private static final Logger log = LoggerFactory.getLogger(DataModelController.class);

    private final DataModelService service;
    private final ProfileService profileService;

    public DataModelController(DataModelService service, ProfileService profileService) {
        this.service = service;
        this.profileService = profileService;
    }

    @GetMapping
    public ResponseEntity<DataModelSummary> summary(@PathVariable String profileId) {
        if (profileService.findById(profileId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(service.summary(profileId));
    }

    @PostMapping
    public ResponseEntity<?> upload(@PathVariable String profileId,
                                    @RequestParam("file") MultipartFile file) {
        if (profileService.findById(profileId).isEmpty()) {
            return ResponseEntity.status(404)
                    .body(Map.of("error", "Profile not found: " + profileId));
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "No file provided (multipart field 'file')"));
        }
        try {
            byte[] bytes = file.getBytes();
            DataModel model = service.saveAndParse(profileId, bytes);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "summary", service.summary(profileId),
                    "message", "DataModel loaded: " + model.tableCount() + " tables."
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            log.warn("DataModel upload failed for {}", profileId, e);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Could not parse file as JSON: " + e.getMessage()));
        }
    }

    @DeleteMapping
    public ResponseEntity<?> delete(@PathVariable String profileId) {
        if (profileService.findById(profileId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        boolean removed = service.delete(profileId);
        return ResponseEntity.ok(Map.of("removed", removed));
    }

    /**
     * List all tables in the cached dataModel with lightweight per-table summaries.
     * Full column and relation details are omitted here — see
     * {@link #tableDetail(String, String)}.
     */
    @GetMapping("/tables")
    public ResponseEntity<?> listTables(@PathVariable String profileId) {
        if (profileService.findById(profileId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Optional<DataModel> model = service.get(profileId);
        if (model.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "No dataModel loaded for this profile. Upload one first."));
        }
        List<TableSummary> summaries = new ArrayList<>();
        for (DataModel.Table t : model.get().tables()) {
            summaries.add(TableSummary.from(t));
        }
        summaries.sort((a, b) -> a.entityName().compareToIgnoreCase(b.entityName()));
        return ResponseEntity.ok(summaries);
    }

    /**
     * Full detail for a single table, looked up case-insensitively by either
     * {@code name} (physical) or {@code entityName} (FAWB).
     */
    @GetMapping("/tables/{tableName}")
    public ResponseEntity<?> tableDetail(@PathVariable String profileId,
                                         @PathVariable String tableName) {
        if (profileService.findById(profileId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Optional<DataModel> model = service.get(profileId);
        if (model.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "No dataModel loaded for this profile."));
        }
        String needle = tableName.toLowerCase(Locale.ROOT);
        for (DataModel.Table t : model.get().tables()) {
            String n = t.name() == null ? "" : t.name().toLowerCase(Locale.ROOT);
            String e = t.entityName() == null ? "" : t.entityName().toLowerCase(Locale.ROOT);
            if (needle.equals(n) || needle.equals(e)) {
                return ResponseEntity.ok(t);
            }
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * Nodes + edges for the ERD canvas view. Keeps the payload cheap — just
     * enough metadata to render a graph without shipping every column.
     */
    @GetMapping("/graph")
    public ResponseEntity<?> graph(@PathVariable String profileId) {
        if (profileService.findById(profileId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Optional<DataModel> model = service.get(profileId);
        if (model.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "No dataModel loaded for this profile. Upload one first."));
        }

        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        for (DataModel.Table t : model.get().tables()) {
            int cols = t.columns() == null ? 0 : t.columns().size();
            int rels = t.relations() == null ? 0 : t.relations().size();
            nodes.add(new GraphNode(t.entityName(), t.name(), t.type(), cols, rels));
            if (t.relations() != null) {
                for (DataModel.Relation r : t.relations()) {
                    // targetTable in dataModel is the physical name; resolve to entityName
                    // for consistent node IDs on the frontend.
                    String targetEntity = resolveEntityName(model.get(), r.targetTable());
                    edges.add(new GraphEdge(
                            t.entityName(),
                            targetEntity != null ? targetEntity : r.targetTable(),
                            r.cardinality(),
                            r.name(),
                            r.virtual()));
                }
            }
        }
        return ResponseEntity.ok(Map.of("nodes", nodes, "edges", edges));
    }

    /** Look up an entityName by physical table name (case-insensitive).
     *  Falls back to the physical name if no entity matches. */
    private static String resolveEntityName(DataModel model, String physicalName) {
        if (physicalName == null) return null;
        String needle = physicalName.toLowerCase(Locale.ROOT);
        for (DataModel.Table t : model.tables()) {
            if (t.name() != null && t.name().toLowerCase(Locale.ROOT).equals(needle)) {
                return t.entityName();
            }
        }
        return null;
    }

    public record GraphNode(String id, String name, String type, int columnCount, int relationCount) {}
    public record GraphEdge(String source, String target, String cardinality, String name, boolean virtual) {}

    /**
     * Lightweight per-table summary for the tables list endpoint. Keeps the
     * response cheap — the browser doesn't need to hydrate every column of
     * every table just to render the left-hand list.
     * <p>
     * {@code columnNames} carries both DB names and field names as strings
     * (no types, no flags) so the frontend can filter the list by column
     * name too. ~1300 columns across 137 tables is a few tens of KB.
     */
    public record TableSummary(
            String name,
            String entityName,
            String type,
            int columnCount,
            int relationCount,
            boolean hasPrimaryKey,
            boolean compositePk,
            List<String> columnNames
    ) {
        static TableSummary from(DataModel.Table t) {
            int cols = t.columns() == null ? 0 : t.columns().size();
            int rels = t.relations() == null ? 0 : t.relations().size();
            boolean pk = t.primaryKey() != null && t.primaryKey().columns() != null
                    && !t.primaryKey().columns().isEmpty();
            boolean composite = t.primaryKey() != null && t.primaryKey().composite();
            List<String> names = new ArrayList<>();
            if (t.columns() != null) {
                for (DataModel.Column c : t.columns()) {
                    if (c.name() != null) names.add(c.name());
                    // Include fieldName too when it differs from the DB name — devs
                    // often search by the Java/JSON name they see in the tree.
                    if (c.fieldName() != null && !c.fieldName().equals(c.name())) {
                        names.add(c.fieldName());
                    }
                }
            }
            return new TableSummary(t.name(), t.entityName(), t.type(), cols, rels, pk, composite, names);
        }
    }
}
