package group.gnometrading.gateways;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * The library's layers: market data and the order gateway's account path are separate, sharing only the order state
 * and rate limits below them. Within the account path, the lane and the reader talk only through the order state's
 * notices, so neither may use the other.
 */
class LayoutTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("group.gnometrading.gateways");

    @Test
    void theLaneAndTheReaderUseNeitherEachOther() {
        noClasses()
                .that()
                .resideInAPackage("..gateways.account.lane..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..gateways.account.reader..")
                .check(CLASSES);
        noClasses()
                .that()
                .resideInAPackage("..gateways.account.reader..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..gateways.account.lane..")
                .check(CLASSES);
    }

    @Test
    void theSharedLayersDontUseTheAccountPath() {
        noClasses()
                .that()
                .resideInAnyPackage("..gateways.orders..", "..gateways.ratelimit..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..gateways.account..")
                .check(CLASSES);
    }

    @Test
    void nothingGenericUsesAVenue() {
        noClasses()
                .that()
                .resideOutsideOfPackage("..gateways.venues..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..gateways.venues..")
                .check(CLASSES);
    }

    @Test
    void marketDataUsesNoOrderCode() {
        noClasses()
                .that()
                .resideInAPackage("..gateways.feed..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..gateways.account..", "..gateways.outbound..", "..gateways.orders..")
                .check(CLASSES);
    }
}
