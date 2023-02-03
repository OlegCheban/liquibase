package liquibase.command.core;

import liquibase.CatalogAndSchema;
import liquibase.Scope;
import liquibase.command.*;
import liquibase.command.providers.ReferenceDatabase;
import liquibase.database.Database;
import liquibase.database.ObjectQuotingStrategy;
import liquibase.diff.DiffGeneratorFactory;
import liquibase.diff.DiffResult;
import liquibase.diff.compare.CompareControl;
import liquibase.diff.output.ObjectChangeFilter;
import liquibase.diff.output.report.DiffToReport;
import liquibase.exception.DatabaseException;
import liquibase.snapshot.*;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.DatabaseObjectFactory;
import liquibase.util.StringUtil;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class DiffCommandStep extends AbstractCommandStep {

    public static final String[] COMMAND_NAME = {"diff"};

    public static final CommandArgumentDefinition<Class[]> SNAPSHOT_TYPES_ARG;
    public static final CommandArgumentDefinition<SnapshotListener> SNAPSHOT_LISTENER_ARG;
    public static final CommandArgumentDefinition<SnapshotControl> REFERENCE_SNAPSHOT_CONTROL_ARG;
    public static final CommandArgumentDefinition<SnapshotControl> TARGET_SNAPSHOT_CONTROL_ARG;
    public static final CommandArgumentDefinition<ObjectChangeFilter> OBJECT_CHANGE_FILTER_ARG;
    public static final CommandArgumentDefinition<CompareControl> COMPARE_CONTROL_ARG;
    public static final CommandArgumentDefinition<String> FORMAT_ARG;
    public static final CommandResultDefinition<DiffResult> DIFF_RESULT;

    static {
        final CommandBuilder builder = new CommandBuilder(COMMAND_NAME);
        SNAPSHOT_TYPES_ARG = builder.argument("snapshotTypes", Class[].class).hidden().build();
        SNAPSHOT_LISTENER_ARG = builder.argument("snapshotListener", SnapshotListener.class).hidden().build();
        REFERENCE_SNAPSHOT_CONTROL_ARG = builder.argument("referenceSnapshotControl", SnapshotControl.class).hidden().build();
        TARGET_SNAPSHOT_CONTROL_ARG = builder.argument("targetSnapshotControl", SnapshotControl.class).hidden().build();
        OBJECT_CHANGE_FILTER_ARG = builder.argument("objectChangeFilter", ObjectChangeFilter.class).hidden().build();
        COMPARE_CONTROL_ARG = builder.argument("compareControl", CompareControl.class).hidden().build();
        FORMAT_ARG = builder.argument("format", String.class).description("Output format. Default: TXT").hidden().build();

        DIFF_RESULT = builder.result("diffResult", DiffResult.class).description("Databases diff result").build();
    }

    @Override
    public List<Class<?>> requiredDependencies() {
        return Collections.singletonList(PreCompareCommandStep.class);
    }

    @Override
    public List<Class<?>> providedDependencies() {
        return Collections.singletonList(DiffCommandStep.class);
    }

    @Override
    public String[][] defineCommandNames() {
        return new String[][] { COMMAND_NAME };
    }

    @Override
    public void adjustCommandDefinition(CommandDefinition commandDefinition) {
        commandDefinition.setShortDescription("Outputs a description of differences.  If you have a Liquibase Pro key, you can output the differences as JSON using the --format=JSON option");
    }

    public static Class<? extends DatabaseObject>[] parseSnapshotTypes(String... snapshotTypes) {
        if ((snapshotTypes == null) || (snapshotTypes.length == 0) || (snapshotTypes[0] == null)) {
            return new Class[0];
        }

        Set<Class<? extends DatabaseObject>> types = DatabaseObjectFactory.getInstance().parseTypes(StringUtil.join(snapshotTypes, ","));

        Class<? extends DatabaseObject>[] returnTypes = new Class[types.size()];
        int i = 0;
        for (Class<? extends DatabaseObject> type : types) {
            returnTypes[i++] = type;
        }

        return returnTypes;
    }

    @Override
    public void run(CommandResultsBuilder resultsBuilder) throws Exception {
        CommandScope commandScope = resultsBuilder.getCommandScope();
        InternalSnapshotCommandStep.logUnsupportedDatabase((Database) commandScope.getDependency(Database.class), this.getClass());

        DiffResult diffResult = createDiffResult(commandScope);
        resultsBuilder.addResult(DIFF_RESULT.getName(), diffResult);

        String printResult = commandScope.getArgumentValue(FORMAT_ARG);
        if (printResult == null || printResult.equalsIgnoreCase("TXT")) {
            Scope.getCurrentScope().getUI().sendMessage("");
            Scope.getCurrentScope().getUI().sendMessage(coreBundle.getString("diff.results"));

            final PrintStream printStream = new PrintStream(resultsBuilder.getOutputStream());
            new DiffToReport(diffResult, printStream).print();
            printStream.flush();
        }
    }

    public DiffResult createDiffResult(CommandScope commandScope) throws DatabaseException, InvalidExampleException {
        DatabaseSnapshot referenceSnapshot = createReferenceSnapshot(commandScope);
        DatabaseSnapshot targetSnapshot = createTargetSnapshot(commandScope);

        final CompareControl compareControl = commandScope.getArgumentValue(COMPARE_CONTROL_ARG);
        referenceSnapshot.setSchemaComparisons(compareControl.getSchemaComparisons());
        if (targetSnapshot != null) {
            targetSnapshot.setSchemaComparisons(compareControl.getSchemaComparisons());
        }

        return DiffGeneratorFactory.getInstance().compare(referenceSnapshot, targetSnapshot, compareControl);
    }

    protected DatabaseSnapshot createTargetSnapshot(CommandScope commandScope) throws DatabaseException, InvalidExampleException {
        CatalogAndSchema[] schemas;
        Database targetDatabase = (Database) commandScope.getDependency(Database.class);

        CompareControl compareControl = commandScope.getArgumentValue(COMPARE_CONTROL_ARG);
        SnapshotControl snapshotControl = commandScope.getArgumentValue(TARGET_SNAPSHOT_CONTROL_ARG);
        Class<? extends DatabaseObject>[] snapshotTypes = commandScope.getArgumentValue(SNAPSHOT_TYPES_ARG);
        SnapshotListener snapshotListener = commandScope.getArgumentValue(SNAPSHOT_LISTENER_ARG);

        if ((compareControl == null) || (compareControl.getSchemaComparisons() == null)) {
            schemas = new CatalogAndSchema[]{targetDatabase.getDefaultSchema()};
        } else {
            schemas = new CatalogAndSchema[compareControl.getSchemaComparisons().length];

            int i = 0;
            for (CompareControl.SchemaComparison comparison : compareControl.getSchemaComparisons()) {
                CatalogAndSchema schema;
                if (targetDatabase.supportsSchemas()) {
                    schema = new CatalogAndSchema(targetDatabase.getDefaultCatalogName(), comparison.getComparisonSchema().getSchemaName());
                } else {
                    schema = new CatalogAndSchema(comparison.getComparisonSchema().getSchemaName(), comparison.getComparisonSchema().getSchemaName());
                }

                schemas[i++] = schema;
            }
        }

        if (snapshotControl == null) {
            snapshotControl = new SnapshotControl(targetDatabase, snapshotTypes);
        }
        if (snapshotListener != null) {
            snapshotControl.setSnapshotListener(snapshotListener);
        }
        ObjectQuotingStrategy originalStrategy = targetDatabase.getObjectQuotingStrategy();
        try {
            targetDatabase.setObjectQuotingStrategy(ObjectQuotingStrategy.QUOTE_ALL_OBJECTS);
            return SnapshotGeneratorFactory.getInstance().createSnapshot(schemas, targetDatabase, snapshotControl);
        } finally {
            targetDatabase.setObjectQuotingStrategy(originalStrategy);
        }
    }

    protected DatabaseSnapshot createReferenceSnapshot(CommandScope commandScope) throws DatabaseException, InvalidExampleException {
        CatalogAndSchema[] schemas;
        Database targetDatabase = (Database) commandScope.getDependency(Database.class);
        Database referenceDatabase = (Database) commandScope.getDependency(ReferenceDatabase.class);

        CompareControl compareControl = commandScope.getArgumentValue(COMPARE_CONTROL_ARG);
        SnapshotControl snapshotControl = commandScope.getArgumentValue(REFERENCE_SNAPSHOT_CONTROL_ARG);
        Class<? extends DatabaseObject>[] snapshotTypes = commandScope.getArgumentValue(SNAPSHOT_TYPES_ARG);
        ObjectChangeFilter objectChangeFilter = commandScope.getArgumentValue(OBJECT_CHANGE_FILTER_ARG);
        SnapshotListener snapshotListener = commandScope.getArgumentValue(SNAPSHOT_LISTENER_ARG);

        if ((compareControl == null) || (compareControl.getSchemaComparisons() == null)) {
            schemas = new CatalogAndSchema[]{targetDatabase.getDefaultSchema()};
        } else {
            schemas = new CatalogAndSchema[compareControl.getSchemaComparisons().length];

            int i = 0;
            for (CompareControl.SchemaComparison comparison : compareControl.getSchemaComparisons()) {
                CatalogAndSchema schema;
                if (referenceDatabase.supportsSchemas()) {
                    schema = new CatalogAndSchema(referenceDatabase.getDefaultCatalogName(), comparison.getReferenceSchema().getSchemaName());
                } else {
                    schema = new CatalogAndSchema(comparison.getReferenceSchema().getSchemaName(), comparison.getReferenceSchema().getSchemaName());
                }
                schemas[i++] = schema;
            }
        }

        if (snapshotControl == null) {
            snapshotControl = new SnapshotControl(referenceDatabase, objectChangeFilter, snapshotTypes);
        }
        if (snapshotListener != null) {
            snapshotControl.setSnapshotListener(snapshotListener);
        }

        ObjectQuotingStrategy originalStrategy = referenceDatabase.getObjectQuotingStrategy();
        try {
            referenceDatabase.setObjectQuotingStrategy(ObjectQuotingStrategy.QUOTE_ALL_OBJECTS);
            return SnapshotGeneratorFactory.getInstance().createSnapshot(schemas, referenceDatabase, snapshotControl);
        } finally {
            referenceDatabase.setObjectQuotingStrategy(originalStrategy);
        }
    }

}

