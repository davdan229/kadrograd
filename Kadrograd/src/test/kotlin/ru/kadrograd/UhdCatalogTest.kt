package ru.kadrograd

import org.junit.Assert.*
import org.junit.Test

class UhdCatalogTest {
    @Test fun onlyFreshUhdIdentitiesCanReachTheShelf() {
        val now = 1_000_000_000L
        val snapshot = """{"version":1,"movies":[
            {"slug":"new-movie","kpId":1,"width":3840,"height":1608,"checked":$now},
            {"slug":"full-hd","kpId":2,"width":1920,"height":1080,"checked":$now},
            {"slug":"../bad","kpId":3,"width":3840,"height":2160,"checked":$now},
            {"slug":"expired","kpId":4,"width":3840,"height":2160,"checked":1},
            {"slug":"future","kpId":5,"width":3840,"height":2160,"checked":${now + 1}}
        ]}"""
        assertEquals(listOf("new-movie"), UhdCatalog().parse(snapshot, now).map { it.slug })
    }
}
