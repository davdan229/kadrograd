# Сторонний код

Кадроград распространяется по GPL-3.0. Название и идентификатор проекта собственные; адаптеры HDRezka и Filmix доработаны на основе открытого кода.

- [recloudstream/TestPlugins](https://github.com/recloudstream/TestPlugins), коммит `d87d65a16e1a1a7e895993b4e254dc00f0519dff`: шаблон сборки и Gradle wrapper; public domain согласно README.
- [lxnhere/cloudstream-ruua-extensions](https://github.com/lxnhere/cloudstream-ruua-extensions), коммит `01bc2b2c76a56f73341c9af8a924ce3834b108ac`: исходные адаптеры HDRezka и Filmix, GPL-3.0. Авторы и предшествующие проекты: lxnhere, hexated, CakesTwix.
- [resoul/filmix](https://github.com/resoul/filmix): исходный формат декодирования Filmix, на который ссылается адаптер lxnhere.
- [recloudstream/gradle](https://github.com/recloudstream/gradle): инструмент упаковки `.cs3`, коммит `7cc64f92d88350ecbf775d6332de14dcaa71dcda`, включён исходниками в `build-logic/` вместе с его лицензией. Это исключает зависимость сборки самого упаковщика от доступности старого артефакта на JitPack.

Изменения Кадрограда: общий парсер потоков, корректные метки качества, объединение серий разных озвучек, ограничение параллельных запросов, отмена операций, настройки адресов, адаптер AniLibria API v1, проверки и публикация.

В `build-logic/build.gradle.kts` зависимость Android Gradle Plugin обновлена до 8.13.2 для совместимости D8 с метаданными Kotlin 2.3.

Runtime-зависимости плагина предоставляются клиентом Cloudstream; в `.cs3` не встраиваются сторонние JAR. HTTP-тесты используют MockWebServer (Square/OkHttp, Apache-2.0) только в тестовом окружении.
