package es.moxlite.i18n

import es.moxlite.data.settings.DeckGrouping
import es.moxlite.data.settings.DeckSorting
import es.moxlite.data.settings.DeckViewMode
import es.moxlite.domain.ColorMatch
import es.moxlite.domain.DeckSort

/** Todos los textos de la app. Implementaciones: [Es] (por defecto) y [En]. */
interface Strings {
    // Navegación y acciones comunes
    val tabMyDecks: String
    val tabSearch: String
    val tabUsers: String
    val tabSettings: String
    val back: String
    val close: String
    val cancel: String
    val accept: String
    val retry: String
    val refresh: String
    val reload: String
    val more: String
    val add: String
    val create: String
    val copy: String
    val edit: String
    val remove: String
    val share: String
    val clear: String
    val hide: String
    val later: String
    val disable: String
    val error: String
    val name: String
    val format: String
    val list: String
    val quantity: String
    fun quantityN(n: Int): String
    val noResults: String

    // Errores
    val errCloudflare: String
    val errUnauthorized: String
    val errNotFound: String
    fun errRateLimited(seconds: Long): String
    fun errServer(code: Int): String
    val errUnreachable: String
    fun errWriteRejected(code: Int, detail: String): String
    val errNotOwner: String
    val errOffline: String
    val errTimeout: String
    fun errFormat(detail: String): String
    val errDeckNotDownloaded: String
    val errNothingRecognized: String

    // Cuenta y sesión
    val account: String
    val notLoggedIn: String
    val loginWithMoxfield: String
    val loginAgain: String
    val loginTitle: String
    val loginHint: String
    val loginExplanation: String
    val loginPrivacyNote: String
    val loginToSeeYourDecks: String
    fun loggedInAs(user: String): String
    val continueWithoutAccount: String
    val welcomeText: String
    val sessionActive: String
    fun tokenRenewsAt(time: String): String
    val sessionWillRenew: String
    val sessionClosedByMoxfield: String
    val accountRowHint: String
    val syncMyDecks: String
    val syncMyDecksHint: String
    val viewPublicProfile: String
    val logout: String
    val logoutConfirm: String
    fun logoutConfirmPending(n: Int): String

    // Cambios pendientes
    val pendingChanges: String
    val noPendingChanges: String
    val pendingAutoUpload: String
    val pendingNeedsLogin: String
    val pendingUpload: String
    val discardChange: String
    val syncNow: String
    fun opCreate(name: String): String
    fun opCreateCopy(name: String): String
    fun opRemove(name: String): String
    fun opMove(name: String, board: String): String
    fun opRename(name: String): String
    fun opPrinting(name: String, set: String, number: String): String

    // Mis mazos
    val myDecks: String
    val noDecksYet: String
    val noDecksYetLoggedIn: String
    fun decksFound(n: Int): String
    val newDeck: String
    val importTextList: String
    val openMoxfieldLink: String
    val openMoxfieldDeck: String
    val deckLinkOrId: String
    val notAMoxfieldLink: String
    val untitledDeck: String
    val onlyOnPhone: String
    val availableOffline: String
    val offlineBanner: String
    val visibility: String
    fun visibilityLabel(visibility: String): String
    val visibilityPrivate: String
    val visibilityLocal: String

    // Mazo
    val deck: String
    val cards: String
    val price: String
    val likes: String
    fun boardLabel(board: String): String
    fun typeLabel(type: String): String
    val allCards: String
    fun manaValueGroup(cmc: Int): String
    fun colorGroup(key: String): String
    val emptyBoard: String
    val saveOffline: String
    val savedOffline: String
    val savingOffline: String
    val noLongerOffline: String
    val shareLink: String
    fun shareMessage(deckName: String, link: String): String
    val copyCode: String
    val codeCopied: String
    fun shareTitle(deckName: String): String
    val exportText: String
    val exportQuestion: String
    val withPrintings: String
    val namesOnly: String
    val copyDeck: String
    val copyName: String
    fun copyOf(name: String): String
    val copyToEdit: String
    val rename: String
    val viewOnMoxfieldWeb: String
    fun moreDecksBy(user: String): String
    val discardLocalChanges: String
    val discardConfirm: String
    val removeFromPhone: String
    val removeConfirm: String
    val removeWithChangesConfirm: String
    val removeLocalOnlyConfirm: String
    val restoredFromMoxfield: String
    val addCard: String
    val finishEditing: String
    val noteConflict: String
    val noteNeedsLogin: String
    val noteLocalOnly: String
    val notePending: String
    val changesKeptLoginToUpload: String
    val tapToFlip: String
    val moveTo: String
    val changeArt: String
    val chooseArt: String
    fun printingChanged(set: String): String
    fun added(name: String): String
    fun viewModeLabel(mode: DeckViewMode): String
    fun groupingLabel(grouping: DeckGrouping): String
    fun sortingLabel(sorting: DeckSorting): String
    val groupBy: String
    val sortBy: String
    val searchCardPlaceholder: String
    val searchCardHint: String

    // Buscar y usuarios
    val searchDecks: String
    val searchDecksPlaceholder: String
    val searchDecksIntro: String
    fun deckSortLabel(sort: DeckSort): String
    val searchDecksColorsHint: String

    // Filtros de mazos (búsqueda y "Mis mazos")
    val filterSort: String
    val filterFormat: String
    fun formatLabel(format: String): String
    val filterColors: String
    val colorless: String
    fun colorMatchLabel(match: ColorMatch): String
    val filterCommander: String
    val commanderPlaceholder: String
    fun commanderNotFound(name: String): String
    val filterBracket: String
    fun bracketLabel(bracket: Int): String
    val filterHub: String
    val hubPlaceholder: String
    val anyOption: String
    val clearFilters: String
    val applyFilters: String
    val noDecksMatch: String

    // "Mis mazos" por pestañas
    val tabMine: String
    val tabSaved: String
    val tabRecent: String
    val searchMyDecksPlaceholder: String
    val noSavedDecks: String
    val noRecentDecks: String
    val noOwnDecksNoAccount: String
    val users: String
    val searchUserPlaceholder: String
    val searchUserHint: String
    val following: String
    val follow: String
    val unfollow: String
    fun userHasNoPublicDecks(user: String): String

    // Importar
    val importList: String
    val deckName: String
    fun cardsRecognized(n: Int): String
    fun notFoundInScryfall(names: String): String
    val importAction: String

    // Web
    val openDeckInApp: String
    val openMoxfieldInApp: String
    val openMoxfieldInAppHint: String
    val moxfieldWeb: String
    val apiLog: String
    val apiLogHint: String
    val apiLogInstructions: String
    val shareLog: String
    val showReadsToo: String
    val onlyWrites: String

    // Ajustes
    val settings: String
    val languages: String
    val appLanguage: String
    fun appLanguageLabel(language: AppLanguage): String
    val cardLanguage: String
    val cardLanguageHint: String
    fun cardLanguageName(code: String): String
    val offlineAndConnection: String
    val autoSaveMine: String
    val autoSaveMineHint: String
    val proxy: String
    val proxyHint: String
    val proxyExplanation: String
    val proxyHost: String
    val proxyPort: String
    val saveAndEnable: String
    val openLinksWithApp: String
    val openLinksWithAppHint: String
    val storage: String
    val savedImages: String
    val about: String
    fun version(name: String): String
    val notifyUpdates: String
    val notifyUpdatesOff: String
    val notifyUpdatesBlocked: String
    val notifyUpdatesOn: String
    val disclaimer: String

    // Actualizaciones
    val updChecking: String
    val updUpToDate: String
    fun updAvailable(version: String): String
    fun updDownloadingVersion(version: String): String
    fun updReady(version: String): String
    val updTapToCheck: String
    val updUpdate: String
    val updSkip: String
    val updDownloading: String
    fun updDownloadingPercent(percent: Int): String
    val updDownloadedHint: String
    val updInstall: String
    val updTitle: String
    val updOpenGithub: String
    fun updAvailableTitle(version: String): String
    fun updYouHave(version: String): String
    fun updCheckFailed(detail: String): String
    fun updDownloadFailed(detail: String): String
    val updNoInstaller: String
    val updChannelName: String
    val updChannelDescription: String
    val updNotificationText: String
    // Asistente EDHREC
    val edhBuilder: String
    val edhImproveDeck: String
    val edhIntro: String
    val edhSearchCommander: String
    val edhSearchPartner: String
    val edhAddPartner: String
    val edhCommanders: String
    fun edhDecksOnEdhrec(n: Int): String
    val edhTheme: String
    val edhBudget: String
    fun edhBudgetLabel(budget: es.moxlite.data.remote.edhrec.EdhBudget): String
    val edhBracket: String
    val edhBracketThemeHint: String
    fun edhAverageLands(n: Int): String
    val edhStartAverage: String
    val edhStartAverageHint: String
    val edhStartEmpty: String
    val edhStartImprove: String
    val edhReplaceWithAverage: String
    val edhReplaceConfirm: String
    val edhLoadingAverage: String
    fun edhAverageLoaded(n: Int): String
    val edhAutoFilling: String
    fun edhAutoFilled(n: Int): String
    val edhNothingToAdd: String
    val edhLandsComplete: String
    fun edhBasicsAdded(n: Int): String
    val edhDeckCreated: String
    val edhDeckUpdated: String
    val edhNotFound: String
    fun edhServerError(code: Int): String
    fun edhRoleLabel(role: es.moxlite.domain.CardRole): String
    fun edhListLabel(tag: String, header: String): String
    val edhSuggestions: String
    val edhCuts: String
    val edhCutsHint: String
    val edhNoCuts: String
    fun edhCutScore(score: Int): String
    val edhForDeck: String
    fun edhRecsFailed(detail: String): String
    fun edhSynergy(percent: Int): String
    fun edhInclusion(percent: Int): String
    fun edhFit(score: Int): String
    fun edhCardsCount(n: Int): String
    fun edhAvgCmc(value: String): String
    val edhAutoFill: String
    val edhBasics: String
    val edhCreateDeck: String
    val edhSaveToDeck: String
    fun edhNot100(n: Int): String
    val edhTargets: String
    val edhTargetsHint: String
    val edhDiscard: String
    val edhDiscardConfirm: String
    val edhOptions: String
    val edhEmptyDeck: String
    val edhNoSuggestions: String
}

/** Nombres de idiomas de cartas (en su propio idioma, como en Scryfall). */
internal val NATIVE_LANGUAGE_NAMES = mapOf(
    "en" to "English", "es" to "Español", "fr" to "Français", "de" to "Deutsch", "it" to "Italiano",
    "pt" to "Português", "ja" to "日本語", "ko" to "한국어", "ru" to "Русский", "zhs" to "简体中文", "zht" to "繁體中文",
)
