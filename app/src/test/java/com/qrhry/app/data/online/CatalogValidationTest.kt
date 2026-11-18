package com.qrhry.app.data.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogValidationTest {
    @Test fun validatesPublishMetadataLimits() {
        assertNull(CatalogValidation.validationError(CatalogPublishMetadata("Opis hry", "sk", "QRhry autor")))
        assertEquals("description", CatalogValidation.validationError(CatalogPublishMetadata(" ", "sk", "QRhry autor")))
        assertEquals("author", CatalogValidation.validationError(CatalogPublishMetadata("Opis", "sk", "A")))
        assertEquals("language", CatalogValidation.validationError(CatalogPublishMetadata("Opis", "not a tag", "Autor")))
    }

    @Test fun normalizesSearchTokensWithoutDiacriticDifferences() {
        assertEquals("stanoviste park", CatalogValidation.normalizeSearchTerm("  Stanovište, PARK! "))
        assertEquals("hrad", CatalogValidation.normalizeSearchTerm("hrad hrad"))
    }
}
