package io.freemytv;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The home screen, styled after the Apple TV app with the macOS 27 glass:
 *  - at the top left, the "For You" title and the tabs (Explore, Movies, Shows); at the top right, icon-only glass
 *    buttons (Live TV, HDMI Inputs, Settings), with the focused one's name below it, and the small crypto quotes
 *    (logo + price) at the right end of the tab row;
 *  - three cards with the titles recommended for right now in the selected tab (Recs, from TMDB); the background is
 *    the first card's photo, heavily blurred and almost white (Backdrop); without picks, one of your own photos
 *    (Photos) the same way, or else a light gradient;
 *  - at the bottom, the glass shelf with the apps (icons in each brand's color, like the tvOS Dock; the focused one
 *    grows);
 *  - ↓ on the shelf: everything moves up and the bottom drawer appears (inputs, shortcuts, weather for the next hours
 *    and quotes with the 24 h change); the background lightens (darkens at night). ↑ or Back closes it;
 *  - from sunset to sunrise, a night look: dark background, smoked glass and white text.
 * Measurements are in a 1920×1080 space (k = width / 1920). The screen is split into layers so the TV's weak GPU only
 * redraws what changed.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener {
    // tvOS grid (Human Interface Guidelines): content inside the safe area, 80 px from the sides and 60 from top and
    // bottom; 40 px between items; 7 columns of 217 px (the apps). The shelf glass extends 36 px past the tiles (the
    // same padding on all four sides) and the tile corners are concentric with it (glass radius = radius + padding).
    private static final float MARGIN = 80, CONTENT_R = 1840;
    // Hero, anchored at the bottom (a two-line title grows upward).
    private static final float HERO_TITLE_W = 900;
    // Shelf: tiles with corners concentric with the glass (glass radius = tile radius + padding).
    // Shelf like the tvOS 27 Dock: icons in each brand's color, 48 apart, and the glass bar hugging them with 40 of
    // padding (the focused icon grows 20% without touching its edge). The icons are 16:9, the shape of the apps' TV
    // banners, so those are never stretched: up to 224 × 126, 178 × 100 with eight apps, 133 × 75 with ten
    // (HomeApps.shelfVisible; past that the shelf scrolls).
    private static final float SHELF_PAD = 40, TILE_GAP = 48;
    private static final float TILE_RADIUS = 22;
    // Tile focus (Apple TV style): it grows from the center and its shadow drops (it "rises" toward the viewer).
    private static final float FOCUS_GROW = 0.2f;
    // Corner capsule.
    // Corner capsule: the height and top offset of the tvOS tab bar (68 and 46).
    private static final float CAPSULE_TOP = 46, CAPSULE_H = 68, BUTTON = 56;
    // Bottom drawer (measured with it open; everything moves up by SHIFT).
    private static final float SHIFT = 560, ROW_TITLE = 518, ROW_TOP = 540, ROW_H = 126, CARDS_TOP = 708, CARDS_H = 300;
    // "All Apps" sheet.
    private static final int COLUMNS = 5, VISIBLE_ROWS = 3;
    private static final float GRID_W = 300, GRID_H = 169, GRID_GAP = 40, GRID_ROW = 239, GRID_TOP = 196, GRID_RADIUS = 22;
    private static final int SMOKE = 0xFF111113, INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7;
    private static final int UP_COLOR = 0xFF30D158, DOWN_COLOR = 0xFFFF453A;
    private static final long TOAST_MS = 6000, FADE_MS = 800;
    /** Blur radii of the shadow and shadowDeep paints (Shadows needs them to draw the same blur on older TVs). */
    private static final float SHADOW_BLUR = 26, DEEP_BLUR = 30;
    private static final int SKY = 0, HERO = 1, STATUS = 2, PLATTER = 3, TILES = 4, DRAWER = 5, SHEET = 6, TOAST = 7, WATCH = 8;
    private static final int Z_STATUS = 0, Z_HERO = 1, Z_SHELF = 2, Z_DRAWER = 3;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    // Light design (the L_ constants): title, tab bar, three recommendation cards and the shelf.
    // The tab bar is a 68 px capsule; the cards sit on the 5-column grid (the wide one spans 3).
    private static final float L_TITLE_BASE = 130, L_TABBAR_TOP = 158, L_TABBAR_H = 68, L_CARDS_TOP = 262, L_CARD_H = 420;
    /** Card slots on screen (one wide, the others tall): a window over the hour's Recs.CARDS picks that scrolls and wraps. */
    private static final int VISIBLE = 3;
    private static final float L_TALL_W = 320, L_CARD_GAP = 40, L_BUTTON = 68, L_BUTTON_GAP = 20, L_BUTTON_CY = 103;
    private static final float L_PLATTER_TOP = 814, L_TILE_H = 126;
    private static final float L_DATE_BASE = 330, L_CLOCK_BASE = 540, L_WEATHER_BASE = 626, L_QUOTES_BASE = 694;
    private static final int L_INK = 0xFF1D1D1F, L_DIM = 0xFF6E6E73, L_FAINT = 0xFF8E8E93;
    private static String[] tabs;
    private static int tabsGeneration = -1;
    /** Slots for the tabs in topFocus/topRects (the round buttons come after): Tabs.ALL at most; tabList is what shows. */
    private static final int TABS = Tabs.ALL.length;
    private List<Tabs.Tab> tabList = Tabs.enabled();
    private int tabsVersion = Prefs.version;
    private static final int Z_TOP = 4, Z_CARDS = 5;
    // Continue Watching (Watching): a row of 16:9 cards under the shelf, what you were watching in each app and where
    // you stopped. ↓ on the shelf lifts the whole screen by W_LIFT to show it (the top scrolls off, as a page does); ↓
    // again opens the drawer, and the row fades as the drawer comes up.
    private static final int Z_WATCH = 10;
    private static final float W_LIFT = 400, W_TITLE_BASE = 686, W_TOP = 712, W_W = 390, W_H = 219, W_GAP = 40, W_RADIUS = 20;
    private static final int W_VISIBLE = 4;
    private List<Watching.Entry> watchItems = new ArrayList<>();
    private Spring[] watchFocus = new Spring[0];
    private RectF[] watchRects = new RectF[0];
    private int watchIndex, watchFirst;
    private final Spring watchScroll = new Spring(0.34f, 1f, 0);   // the row's x offset while it scrolls to the focused card
    private final Spring peek = new Spring(0.46f, 0.92f, 0);
    private boolean peekOpen, watchDirty, peekWasMoving;
    private final Map<String, HomeApps.Item> watchTiles = new HashMap<>();   // an app's tile, for a card without a picture
    private final Map<String, LinearGradient> watchGradients = new HashMap<>();   // a card's brand gradient, one per app
    private final Watching watching;
    private Paint wTitle, wMeta;
    private LinearGradient wScrim;   // unit gradient under a card's progress bar, placed with shaderMatrix
    private static final String REMOVE_WATCH = "remove-watch";
    private static final int WATCH_MENU = 6;   // menuPills: a Continue Watching card's hold menu (one pill: remove)
    private Watching.Entry menuEntry;
    // Search page (the remote's Assistant button, or the magnifier left of the tabs), on the sheet layer: a glass
    // field and a mic button at the top, the results as cards in the All Apps grid below.
    private static final int SEARCH_SLOT = TABS + HomeApps.MAX_TOP;   // the magnifier's slot in topFocus/topRects
    private static String tabsWindow = "";   // the selected tab's name carries its window or language: made again when they change
    private static int tabsSelected = -1;
    private static final int S_FIELD = 0, S_MIC = 1, S_RESULTS = 2, S_CLEAR = 3;   // Clear: right of the mic, while there is something to clear
    private static final float S_FIELD_W = 1180, S_FIELD_TOP = 112, S_FIELD_H = 80, S_GRID_TOP = 290;
    /** Results: 4 cards of 390 × 219 a row (the pictures are 384 × 216: drawn nearly pixel for pixel), the title and a
     *  second line under each, left aligned like the home cards; two rows fit, the rest scroll. */
    private static final float S_W = 390, S_H = 219, S_GAP = 40, S_ROW = 345, S_RADIUS = 20;
    private static final int S_COLS = 4, S_ROWS = 2;

    private final Activity activity;
    private final Scene scene = new Scene();
    private final HomeData data;
    private final Recs recs;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences ui;
    private final TvInputManager tvInputs;
    private final Spoken spoken = new Spoken(this, new Spoken.Source() {
        @Override public void items(List<Integer> out) { a11yItems(out); }
        @Override public String text(int id) { return a11yText(id); }
        @Override public boolean bounds(int id, Rect out) { return a11yBounds(id, out); }
        @Override public int focused() { return a11yFocused(); }
        @Override public void focus(int id) { a11yFocus(id); }
        @Override public void click(int id) { if (infoOpen) infoAct(); else activate(); }
        @Override public boolean clickable(int id) { return (id - 1) / 1000 != WIDGETS; }
        @Override public boolean holdable(int id) {
            int group = (id - 1) / 1000, i = (id - 1) % 1000;
            return (group == GRID && canToggleShelf(i)) || (group == SEARCH && i >= 3 && searchOpen && i - 3 < results.size() && results.get(i - 3).detailed)
                    || (group == Z_WATCH && !covered() && i < watchItems.size());
        }
        @Override public void hold(int id) { a11yHold(); }
    });
    private final RectF a11yRect = new RectF();
    private final Layer sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer, watchLayer;
    private int zone = Z_SHELF;

    // Night look (like Apple's "Automatic" appearance): darker from sunset to sunrise, with a 2 s transition.
    private static final long NIGHT_MS = 2000;
    private boolean nightTarget;
    private float night, nightFrom;     // 0 = day, 1 = night
    private long nightAt;
    private final int[] glassNight = new int[Backdrop.GW * Backdrop.GH], frostNight = new int[Backdrop.FW * Backdrop.FH];
    private final int[] neutralGlassPx = new int[Backdrop.GW * Backdrop.GH], neutralFrostPx = new int[Backdrop.FW * Backdrop.FH];
    private boolean nightPair;          // the glass holds both photos (day and night), blended by `night`
    private Shader nightGradient;

    // At the top: tabs (0 to 2) and icon-only buttons (3 to 5); the three cards
    private int tab, topIndex, cardIndex;
    /** The pick in the first slot (the window's start) and the pick whose backdrop is the background. */
    private int cardStart, featuredCard = -1;
    private int widgetIndex;    // with TalkBack on, the drawer's weather (0) or quotes (1) card can take the focus
    private List<Recs.Pick> cards = new ArrayList<>();
    // The tabs (TABS slots), then the round buttons at the top right (up to HomeApps.MAX_TOP).
    private final Spring[] topFocus = new Spring[TABS + HomeApps.MAX_TOP + 1];   // + the magnifier (SEARCH_SLOT)
    private final RectF[] topRects = new RectF[TABS + HomeApps.MAX_TOP + 1];
    private final Spring[] cardFocus = new Spring[VISIBLE];
    /** Accordion: 1 for the wide card, 0 for the tall ones; the featured card is the focused one (or the last focused). */
    private final Spring[] cardWide = new Spring[VISIBLE];
    /** The row slides when the window over the picks moves on (±1 → 0); slidePick: the pick that leaves. */
    private final Spring slide = new Spring(0.36f, 0.9f, 0);
    private int slidePick = -1;
    private final RectF slideRect = new RectF(), slideA = new RectF(), slideB = new RectF();
    // The card's menu (hold OK on a pick): a row of round buttons under the card, in the home screen's own style.
    private boolean menuOpen, menuOkDown;
    /** Hold OK on a tab: its choice as text pills. Which: the Trending windows, Pan-India's languages, a tab's movies/series. */
    private static final int NO_PILLS = 0, WINDOWS = 1, LANGUAGES = 2, KINDS = 3, RATINGS = 4, OTHERS = 5;   // RATINGS: "Watched", under a card; OTHERS: Upcoming's language
    private static final String[] RATING_ACTIONS = {"rate:4", "rate:6", "rate:8", "rate:10"};
    private int menuPills;
    private Tabs.Tab menuTab;
    private Recs.Pick menuPick;
    private String menuVerdict;   // the verdict already given for menuPick, lit in the row
    private final RectF menuCard = new RectF();
    private final RectF menuPanel = new RectF();   // a tab's menu: the panel under the tab bar its rows of pills sit in
    private final Path menuClip = new Path();   // the card (or tab pill) left bright: a rounded hole in the dim
    private final List<String> menuActions = new ArrayList<>();
    private int menuIndex;
    private final Spring menu = new Spring(0.3f, 0.85f, 0);
    private static final int MENU_MAX = 10;   // the Trending tab's menu: 2 lists, 4 windows, 3 kinds
    private final Spring[] menuFocus = new Spring[MENU_MAX];
    private final RectF[] menuRects = new RectF[MENU_MAX];
    private int featured;
    private final RectF[] cardRects = new RectF[VISIBLE];
    // Info page (hold OK on a title → Info): the title in full, over everything, in the toast layer.
    private static final float I_COLUMN = 980, I_IMAGE_L = 1100, I_IMAGE_T = 100, I_IMAGE_W = 740, I_ROW_CY = 900;
    private boolean infoOpen;
    private Recs.Pick infoPick;
    private int infoIndex;
    private final List<String> infoActions = new ArrayList<>();   // INFO page row: TRAILER, the services' keys, the verdicts
    private final List<HomeApps.Item> infoItems = new ArrayList<>();   // the services' tiles, one per action (null for a round button)
    private final Spring info = new Spring(0.3f, 0.85f, 0);
    private final Spring[] infoFocus = new Spring[MENU_MAX];
    private final RectF[] infoRects = new RectF[MENU_MAX];
    private final RectF infoImage = new RectF();
    private StaticLayout infoTitle, infoOverview;
    private String infoMeta = "", infoDirector = "", infoCast = "";   // already cut to the column (nothing is built per frame)
    private final String[] infoPills = new String[4], infoLabels = new String[MENU_MAX];
    private TextPaint iTitle, iBody;
    private Paint iMeta, iLabel, iPill;
    // Search page
    private boolean searchOpen, sheetIsSearch, searchDirty, editing, jumpToResults;
    private int sZone = S_FIELD, sIndex;
    private List<Recs.Pick> results = new ArrayList<>();
    private Spring[] resultFocus = new Spring[0];
    private final Spring fieldFocus = new Spring(0.3f, 0.82f, 0), micFocus = new Spring(0.3f, 0.82f, 0), clearFocus = new Spring(0.3f, 0.82f, 0);
    private final Spring sScroll = new Spring(0.34f, 1f, 0);
    /** 1 while the focus is among the results (they show in full), 0.65 while it is on the field or the mic (they wait, dimmer). */
    private final Spring resultsLit = new Spring(0.3f, 0.9f, 0.65f);
    private final RectF fieldRect = new RectF(), micRect = new RectF(), clearRect = new RectF(), resultCell = new RectF();
    private Field field;   // what the TV's keyboard types into (never visible: the field is drawn by hand)
    private String[] resultLabels, resultMetas;
    private Paint sQuery, sHint;
    private TextPaint sMeta, sTitle;
    private final Runnable runSearch = new Runnable() {
        @Override public void run() { recs.search(field.getText().toString()); }
    };
    private final Matrix shaderMatrix = new Matrix();
    private final List<HomeApps.Item> lightButtons = new ArrayList<>();
    private Paint lTitle, lTab, lTabOn, lCardTitle, lCardMeta, lQuote, lTip, lDate, lClock, lWeather, lClockQuote;
    private Paint lScore, scorePill;   // TMDB's user score on the cards: white on a dark pill
    private Shader lightGradient;

    // Shelf
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private Spring[] tileFocus = new Spring[0];
    private RectF[] tileRects = new RectF[0];
    private final Map<String, Bitmap> crispLogos = new HashMap<>();   // logos already sized for the focused tile
    /** Each app's tile like its Apple TV icon: brand colors (top, bottom) and logo (0 = as is, 1 = white, 2 = dark). */
    private static final Map<String, int[]> TILE_STYLE = new HashMap<>();
    static {
        TILE_STYLE.put("netflix", new int[] {0xFF0A0A0A, 0xFF000000, 0});
        TILE_STYLE.put("youtube", new int[] {0xFF1C1C1E, 0xFF09090A, 0});
        TILE_STYLE.put("globoplay", new int[] {0xFFFF8A1F, 0xFFFF2D5A, 1});
        TILE_STYLE.put("prime", new int[] {0xFF2AA8FF, 0xFF0A68D4, 1});
        TILE_STYLE.put("disney", new int[] {0xFF173584, 0xFF08143F, 0});
        TILE_STYLE.put("max", new int[] {0xFF5C30E8, 0xFF221684, 0});
        TILE_STYLE.put("appletv", new int[] {0xFF1C1C1E, 0xFF000000, 0});
    }
    private final android.graphics.PorterDuffColorFilter whiteLogo =
            new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
    private final RectF platterRect = new RectF();
    private int shelfIndex;
    /** The first app the shelf shows: with more apps than fit, it scrolls with the focus. */
    private int shelfFirst;
    private final int shelfVisible;

    // Moving apps on the shelf (as on Apple TV): hold OK on a tile, then ← → move it, OK drops it, Back cancels. The
    // lifted tile wiggles; the others slide to their new places (each slot's x offset is a spring that returns to 0).
    private static final long HOLD_MS = 500, WIGGLE_MS = 300;
    private static final float LIFT_GROW = 0.08f, WIGGLE_DEG = 1.5f;
    private boolean reordering, reorderAnim, okHeld, holdArmed;
    private int liftIndex = -1;
    private long reorderAt;
    private float wiggle;
    private Spring[] tileSlide = new Spring[0];
    private final Spring moveLift = new Spring(0.3f, 0.72f, 0);
    private List<HomeApps.Item> reorderOrigin;
    private final Runnable holdStart = () -> { if (this.okDown && this.holdArmed) hold(); };

    // Corner capsule
    private List<HomeApps.Item> statusItems = new ArrayList<>();
    private Spring[] statusFocus = new Spring[0];
    private RectF[] statusRects = new RectF[0];
    private final RectF capsuleRect = new RectF();
    private float capsuleInfoW;
    private int statusIndex;

    // Hero and background
    private List<Recs.Pick> picks = new ArrayList<>();
    private int pickIndex, heroSlide = 1;
    private Hero hero, heroFrom;
    private long heroFadeAt, bgFadeAt;
    private final Spring heroFocus = new Spring(0.28f, 0.85f, 0);
    private final RectF heroButton = new RectF();
    private Backdrop bgFrom, bgTo;     // null = gradient (no photo)
    private final int[] frostMix = new int[Backdrop.FW * Backdrop.FH];

    // Bottom drawer
    private List<HomeApps.Item> drawerItems = new ArrayList<>();
    private int drawerInputs;          // how many leading items are inputs (the rest are shortcuts)
    private Spring[] drawerFocus = new Spring[0];
    private RectF[] drawerRects = new RectF[0];
    private final RectF weatherCard = new RectF(), quotesCard = new RectF();
    private final Spring drawer = new Spring(0.46f, 0.92f, 0);
    private boolean drawerOpen;
    private int drawerIndex;

    // "All Apps" sheet
    private List<HomeApps.Item> others = new ArrayList<>();
    private Spring[] gridFocus = new Spring[0];
    private String[] gridLabels;
    private final Spring sheet = new Spring(0.38f, 0.9f, 0);
    private final Spring scroll = new Spring(0.34f, 1f, 0);
    private final RectF focusedCell = new RectF();
    private boolean sheetOpen, shelfEdited;
    private int gridIndex;
    private long toggledAt;

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;

    // Frames
    private boolean running, tilesDirty, statusDirty, heroDirty, drawerDirty, gridDirty, motionDirty = true;
    private boolean drawerWasMoving, idle;
    private long lastNanos, resumedAt, lastStep;
    private static final long BURST_MS = 1500;
    private int w, h;
    private float k = 1;
    private String clockText = "", dateText = "", weatherLine = "";
    private long weatherLineAt = -1;
    private int weatherLinePrefs = -1;
    private String weatherLineLanguage = "";
    private long clockMinute = -1;
    private boolean okDown, backDown;

    private Paint labelPaint, metaPaint, clockPaint, buttonPaint, capsuleText, tipPaint, quotePaint, staleQuote;
    private Paint rowTitle, drawerLabel, cardTitle, cardText, cardDim, cardDimRight, bigTemp, hourPaint, hourTemp;
    private Paint pricePaint, changePaint, sheetTitle;
    private TextPaint titlePaint, gridLabel, toastPaint;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowSoft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shadows shadows = new Shadows();
    private final Paint imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF(), cell = new RectF(), screen = new RectF();
    private final TextPaint fitPaint = new TextPaint();
    private final RectF gridCell = new RectF();
    private LinearGradient[] tileShaders = new LinearGradient[0];
    private LinearGradient tileGlow, cardSheen;
    private float tileShaderH, cardSheenH;
    private LinearGradient lightSheen, lightRim;   // unit-square gradients, placed with shaderMatrix (cards change width)
    private android.graphics.ColorFilter veilFilter;
    private float veilAmount = -1;
    private int veilColor;
    private HomeData.Weather textWeather;
    private long textPricesAt = -1, textMinute = -1;
    private int textPrefs = -1;
    private String textLanguage;
    private String weatherTitle, nowTemp, highLow, sunriseText, sunsetText, staleText;
    private final String[] hourText = new String[6], hourTempText = new String[6];
    private final String[] priceText = new String[HomeData.COINS.length], changeText = new String[HomeData.COINS.length];

    /** What the hero shows: a recommendation or, without any, the date, the time and the weather. */
    private static final class Hero {
        String label = "", title = "", meta = "";
        boolean clock, button;
        int dots, dot;
        StaticLayout titleLayout;

        boolean sameText(Hero o) {
            return o != null && clock == o.clock && label.equals(o.label) && title.equals(o.title) && meta.equals(o.meta);
        }
    }

    /** One layer of the screen. It draws in full-screen coordinates: the glass needs each pixel's real position. */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override protected void onDraw(Canvas c) {
            if (labelPaint == null) return;
            c.translate(-getLeft(), -getTop());
            // The layer may be shifted (bottom drawer): the glass reads the background from where it really is. The
            // drawer and the top buttons are always drawn as if resting in place (they are only visible there and are
            // not redrawn while they move).
            scene.setOffset(0, kind == DRAWER || kind == STATUS ? 0 : getTranslationY());
            shadows.dither = dark() > 0.5f;
            switch (kind) {
                case SKY: drawSky(c); break;
                case HERO: drawLightTop(c); break;
                case STATUS: drawLightButtons(c); break;
                case PLATTER: drawPlatter(c); break;
                case TILES: drawTiles(c); break;
                case DRAWER: drawDrawer(c); break;
                case WATCH: drawWatch(c); break;
                case SHEET: if (sheetIsSearch) drawSearch(c); else drawSheet(c); break;
                default: drawInfo(c); drawMenu(c); drawToast(c); break;
            }
            scene.setOffset(0, 0);
        }
    }

    private final Runnable reloadSoon = this::reload;
    private static final long APPS_MAX_AGE_MS = 15 * 60_000;
    private final LauncherApps launcherApps;
    private boolean appsStale = true;
    private int appsVersion, appsGeneration;
    private long appsAt;

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reloadSoon(); }
        @Override public void onInputAdded(String inputId) { reloadSoon(); }
        @Override public void onInputRemoved(String inputId) { reloadSoon(); }
        @Override public void onInputUpdated(String inputId) { reloadSoon(); }   // a CEC device told its name
    };

    private void reloadSoon() {
        handler.removeCallbacks(reloadSoon);
        handler.postDelayed(reloadSoon, 500);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            wake();
            data.refreshIfStale();
            recs.refreshIfStale();
            watching.refreshStremioIfStale();
            updateWatching();
            checkNotices();
            updatePalette();
            checkNight(true);
            updatePicks();      // three new ones every hour
            handler.postDelayed(this, 15_000);
        }
    };

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        recs = Recs.get(a);
        click = new Click(a);
        shelfVisible = HomeApps.shelfVisible(a);
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        launcherApps = (LauncherApps) a.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        if (launcherApps != null) launcherApps.registerCallback(packages, handler);
        setFocusable(true);
        sky = new Layer(a, SKY);
        heroLayer = new Layer(a, HERO);
        status = new Layer(a, STATUS);
        platter = new Layer(a, PLATTER);
        tiles = new Layer(a, TILES);
        drawerLayer = new Layer(a, DRAWER);
        watchLayer = new Layer(a, WATCH);
        sheetLayer = new Layer(a, SHEET);
        toastLayer = new Layer(a, TOAST);
        // Hardware layers on the GPU: only the one that changed is redrawn; moving, fading and scaling stay cheap.
        // Not on Android 8 to 11: on older GPUs (a Mi Box 3's Mali-450) redrawing and compositing a big layer on every
        // frame cost more than drawing straight to the screen (measured: focus on the picks 33 → 12 ms a frame, the
        // drawer 19 → 17 ms).
        for (View v : new View[] {heroLayer, status, platter, tiles, drawerLayer, watchLayer, sheetLayer}) v.setLayerType(Ui.LEGACY ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE, null);
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer, watchLayer, sheetLayer, toastLayer}) {
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // TalkBack reads the items (Spoken)
            addView(v);
        }
        setContentDescription("Free My TV");
        field = new Field(a);
        addView(field);
        // The keyboard must neither push the screen up nor open by itself: the search page asks for it.
        a.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        drawerLayer.setVisibility(INVISIBLE);
        watchLayer.setVisibility(INVISIBLE);
        watching = Watching.get(a);
        watching.setListener(() -> {   // a picture is ready, or Stremio's list came in
            if (!running) return;
            watchDirty = true;
            updateWatching();
            wake();
        });
        for (int i = 0; i < topFocus.length; i++) {
            topFocus[i] = new Spring(0.26f, 0.85f, 0);
            topRects[i] = new RectF();
        }
        for (int i = 0; i < VISIBLE; i++) {
            cardFocus[i] = new Spring(0.32f, 0.78f, 0);
            cardWide[i] = new Spring(0.36f, 0.9f, i == 0 ? 1 : 0);
            cardRects[i] = new RectF();
        }
        for (int i = 0; i < menuFocus.length; i++) {
            menuFocus[i] = new Spring(0.26f, 0.85f, 0);
            menuRects[i] = new RectF();
            infoFocus[i] = new Spring(0.26f, 0.85f, 0);
            infoRects[i] = new RectF();
        }
        topRects[SEARCH_SLOT].set(0, 0, 1, 1);
        setLightButtons(HomeApps.topButtons());
        recs.setLight(true);
        checkNight(false);
        sheetLayer.setVisibility(INVISIBLE);
        scene.setMotion(0.6f, 0f);
        updatePalette();
        // First time: right away (a cold start opens with the apps already there); missing logos come from reload().
        setShelf(HomeApps.shelf(a, false));
        setStatusItems(statusList(a));
        setDrawerItems(drawerList(a));
        updateWatching();
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        if (running) return;
        running = true;
        checkNight(false);
        long now = SystemClock.uptimeMillis();
        // Some remotes (Xiaomi's) send one Home press as a burst of 5 to 8, and each one pauses and resumes the home
        // screen: those after the first change nothing, so the lists are read only once.
        boolean again = resumedAt != 0 && now - resumedAt < BURST_MS;
        resumedAt = now;
        lastNanos = 0;
        lastStep = 0;
        data.addListener(this);
        recs.addListener(this);
        data.refreshIfStale();
        recs.refreshIfStale();
        Photos.importInbox(getContext(), ownReady);   // photos a computer just sent
        updateWatching();   // what was played while an app was open
        watching.refreshStremioIfStale();
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        handler.postDelayed(tick, 15_000);
        checkNotices();
        if (!again) reload();
        updatePalette();
        updatePicks();
        if (restorePending) restoreState();
        if (sheetOpen) closeSheet();
        if (searchOpen) updateResults();   // what came in while the recognizer (or an app) was on top
        sheet.snap(sheet.target);
        motionDirty = true;
        invalidateAll();
        idle = false;
        Choreographer.getInstance().postFrameCallback(this);
        spoken.readAgain();
    }

    boolean isRunning() { return running; }

    /**
     * Where you were (the tab, the zone, the focused card or app), saved when the home screen pauses and put back when
     * it comes up again: opening an app and coming back lands on the same card, and so does a restart of the home
     * screen's process (Android stops it behind a big app on a 2.4 GB TV).
     */
    private void saveState() {
        SharedPreferences.Editor e = ui.edit().putString("tab", tabList.get(Math.min(tab, nTabs() - 1)).id).putInt("zone", zone);
        e.putString("card", zone == Z_CARDS && cardIndex < cards.size() ? Recs.key(cards.get(cardIndex)) : "");
        e.putInt("slot", zone == Z_CARDS ? slotOf(cardIndex) : 0);
        e.putString("app", zone == Z_SHELF && shelfIndex < shelf.size() ? String.valueOf(shelf.get(shelfIndex).key) : "");
        e.putInt("top", zone == Z_TOP ? topIndex : -1);
        e.putInt("watch", zone == Z_WATCH ? watchIndex : -1);
        e.apply();
    }

    private boolean restorePending = true;   // once, at the first resume (the lists are read by then)

    private void restoreState() {
        restorePending = false;
        String tabId = ui.getString("tab", "");
        for (int i = 0; i < nTabs(); i++) if (tabList.get(i).id.equals(tabId)) selectTab(i);
        int z = ui.getInt("zone", Z_SHELF);
        if (z == Z_CARDS && picksOn()) {
            String key = ui.getString("card", "");
            int n = cards.size(), at = -1;
            for (int i = 0; i < n; i++) if (Recs.key(cards.get(i)).equals(key)) at = i;
            if (at >= 0) {   // the same card in the same slot (the hour may have moved the set on: then the first one)
                zone = Z_CARDS;
                cardIndex = at;
                cardStart = n > VISIBLE ? ((at - Math.min(ui.getInt("slot", 0), VISIBLE - 1)) % n + n) % n : 0;
                heroDirty = true;
            }
        } else if (z == Z_TOP && picksOn()) {
            int top = ui.getInt("top", -1);
            if (top >= 0 && (top < nTabs() || top == SEARCH_SLOT || (top >= TABS && top < TABS + lightButtons.size()))) {
                zone = Z_TOP;
                topIndex = top;
            }
        } else if (z == Z_SHELF) {
            String key = ui.getString("app", "");
            for (int i = 0; i < shelf.size(); i++) if (String.valueOf(shelf.get(i).key).equals(key)) shelfIndex = i;
        } else if (z == Z_WATCH && !watchItems.isEmpty()) {   // back from a title opened from the row: the row, on that card
            openWatch();
            watchIndex = Math.max(0, Math.min(watchItems.size() - 1, ui.getInt("watch", 0)));
            peek.snap(1);
            motionDirty = true;
        }
        retarget(true);
    }

    void pause() {
        if (!running) return;
        running = false;
        if (speech != null) {   // an app or the screensaver came over: the microphone is let go
            speech.destroy();
            speech = null;
            listening = false;
        }
        saveState();
        cancelReorder(true);
        handler.removeCallbacks(holdStart);
        okHeld = holdArmed = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(reloadSoon);
        data.removeListener(this);
        recs.removeListener(this);
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
    }

    void destroy() {
        pause();
        if (launcherApps != null) launcherApps.unregisterCallback(packages);
        click.release();
    }

    /** Starting state (Home pressed while the home screen is showing): first app focused, drawer and sheet closed. */
    void reset(boolean animated) {
        wake();
        cancelReorder(!animated);
        if (infoOpen) closeInfo();
        if (searchOpen) closeSearch();
        if (menuOpen) closeMenu();
        zone = Z_SHELF;
        shelfIndex = 0;
        sheetOpen = false;
        drawerOpen = false;
        peekOpen = false;
        if (animated) {
            sheet.target = 0;
            drawer.target = 0;
            peek.target = 0;
        } else {
            sheet.snap(0);
            drawer.snap(0);
            peek.snap(0);
        }
        if (pickIndex != 0 && !picks.isEmpty()) {
            pickIndex = 0;
            updatePicks();
        }
        if (tab != 0) selectTab(0);
        retarget(!animated);
        motionDirty = true;
    }

    /** The round buttons at the top right (Settings > Top Buttons), with their names in the current language. */
    private void setLightButtons(List<HomeApps.Item> list) {
        lightButtons.clear();
        lightButtons.addAll(list);
        if (zone == Z_TOP && topIndex != SEARCH_SLOT && topIndex >= TABS + lightButtons.size()) {
            topIndex = lightButtons.isEmpty() ? nTabs() - 1 : TABS + lightButtons.size() - 1;
            if (topIndex < TABS && !picksOn()) zone = Z_SHELF;   // without the picks the tabs can't hold focus
            else if (topIndex < TABS) selectTab(topIndex);
            retarget(true);
        }
        layoutLight();
        statusDirty = true;
    }

    /** The tab names, made again only when the language changes (they are drawn on every frame). */
    /** The tab names; the selected Trending tab says its window, the selected Pan-India tab its language. */
    private String[] tabs() {
        Tabs.Tab selected = tabList.get(Math.min(tab, nTabs() - 1));
        String state = recs.trendingWindow() + "/" + recs.panLanguage() + "/" + recs.tabKind(selected) + "/" + recs.otherLanguage();
        if (tabsGeneration != Ui.generation || tabs == null || tabs.length != tabList.size() || !state.equals(tabsWindow) || tabsSelected != tab) {
            tabs = new String[tabList.size()];
            for (int i = 0; i < tabs.length; i++) {
                Tabs.Tab t = tabList.get(i);
                String name = Ui.tr(t.label);
                if (t.trending && Recs.rated(recs.trendingWindow())) name = Ui.tr("Top rated");   // the tab is named after the list it shows
                if (i == tab && t.trending) name += " · " + menuLabel(recs.trendingWindow());
                if (i == tab && t.pan && !recs.panLanguage().isEmpty()) name += " · " + Ui.tr(Scorer.languageName(recs.panLanguage()));
                if (i == tab && t.kind == null && !recs.tabKind(t).isEmpty()) name += " · " + menuLabel("kind:" + recs.tabKind(t));
                if (i == tab && t.other && !recs.otherLanguage().isEmpty()) name += " · " + menuLabel("lang:" + recs.otherLanguage());
                tabs[i] = name;
            }
            tabsGeneration = Ui.generation;
            tabsWindow = state;
            tabsSelected = tab;
        }
        return tabs;
    }

    private int nTabs() {
        return tabList.size();
    }

    /** Home Settings (another process) changed the tabs: read them again; the selected tab stays if it is still there. */
    private void refreshTabs() {
        if (Prefs.version == tabsVersion) return;
        tabsVersion = Prefs.version;
        List<Tabs.Tab> list = Tabs.enabled();
        if (list.equals(tabList)) return;
        String current = tab < tabList.size() ? tabList.get(tab).id : "";
        tabList = list;
        tabs = null;
        tab = 0;
        for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(current)) tab = i;
        if (zone == Z_TOP && topIndex < TABS) topIndex = Math.min(topIndex, nTabs() - 1);
        layoutLight();
        updateCards();
        heroDirty = statusDirty = true;
        retarget(true);
    }

    @Override public void onData() {
        wake();
        updatePalette();
        checkNight(true);
        layoutStatus();
        heroDirty = statusDirty = drawerDirty = true;
        platter.invalidate();
    }

    @Override public void onRecs() {
        wake();
        updatePicks();
        updateResults();
    }

    private void invalidateAll() {
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer}) v.invalidate();
    }

    // MARK: - Size and layers

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float kk = width / 1920f;
        measure(sky, width, height);
        measure(heroLayer, width, Math.round(790 * kk));
        measure(status, Math.round(1160 * kk), Math.round(240 * kk));
        // Sized to what they draw (the glass and its shadow; the grown tiles and their shadows): smaller layers are
        // cheaper for the GPU to composite on every frame of the drawer animation.
        measure(platter, width, Math.round(320 * kk));
        measure(tiles, width, Math.round(270 * kk));
        measure(drawerLayer, width, Math.round(600 * kk));
        measure(watchLayer, width, Math.round(440 * kk));
        measure(sheetLayer, width, height);
        measure(toastLayer, width, height);   // full screen: the toasts at the top, the card's menu where its card is
        measure(field, Math.round(S_FIELD_W * kk), Math.round(S_FIELD_H * kk));
        setMeasuredDimension(width, height);
    }

    private static void measure(View v, int width, int height) {
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l, height = b - t;
        float kk = width / 1920f;
        sky.layout(0, 0, width, height);
        place(heroLayer, 0, 0);
        place(status, width - status.getMeasuredWidth(), 0);
        place(platter, 0, Math.round(750 * kk));
        place(tiles, 0, Math.round(790 * kk));
        place(drawerLayer, 0, Math.round(470 * kk));
        place(watchLayer, 0, Math.round(640 * kk));
        sheetLayer.layout(0, 0, width, height);
        sheetLayer.setPivotX(width / 2f);
        sheetLayer.setPivotY(height / 2f);
        toastLayer.layout(0, 0, width, height);
        place(field, Math.round((1920 - S_FIELD_W - 20 - L_BUTTON) / 2 * kk), Math.round(S_FIELD_TOP * kk));
    }

    private static void place(View v, int x, int y) {
        v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        scene.setSize(w, h);
        makePaints();
        gridLabels = null;
        clockMinute = -1;
        updateClock();
        Symbols.warm(getContext(), "bell", 26 * k);
        layoutShelf();
        layoutStatus();
        layoutDrawer();
        layoutLight();
        layoutWatch();
        hero = null;
        heroFrom = null;
        updatePicks();
        motionDirty = true;
        wake();
    }

    private void makePaints() {
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xC7FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        titlePaint = new TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(0.005f);
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xD1FFFFFF);
        metaPaint.setLetterSpacing(0.014f);
        clockPaint = Ui.numbers(Ui.thin, 176 * k, 0xFFFFFFFF);
        clockPaint.setLetterSpacing(-0.01f);
        for (Paint p : new Paint[] {labelPaint, titlePaint, metaPaint, clockPaint}) p.setShadowLayer(22 * k, 0, 2 * k, 0x59000000);
        lTitle = Ui.text(Ui.displaySemibold, 76 * k, L_INK);
        lTitle.setLetterSpacing(0.004f);
        lTab = Ui.text(Ui.medium, 30 * k, L_DIM);
        lTabOn = Ui.text(Ui.semibold, 30 * k, L_INK);
        lCardTitle = Ui.text(Ui.semibold, 31 * k, L_INK);
        lCardTitle.setLetterSpacing(0.012f);
        lCardMeta = Ui.text(Ui.medium, 25 * k, L_DIM);
        wTitle = Ui.text(Ui.semibold, 27 * k, L_INK);
        wTitle.setLetterSpacing(0.01f);
        wMeta = Ui.text(Ui.medium, 22 * k, L_DIM);
        lScore = Ui.numbers(Ui.semibold, 20 * k, 0xFFFFFFFF);
        scorePill = new Paint(Paint.ANTI_ALIAS_FLAG);
        scorePill.setColor(0xA6000000);
        lQuote = Ui.numbers(Ui.regular, 21 * k, L_FAINT);
        lTip = Ui.text(Ui.semibold, 25 * k, L_INK);
        lTip.setTextAlign(Paint.Align.CENTER);
        lDate = Ui.text(Ui.semibold, 34 * k, L_DIM);
        lClock = Ui.numbers(Ui.light, 200 * k, L_INK);
        lClock.setLetterSpacing(-0.01f);
        lWeather = Ui.text(Ui.medium, 34 * k, L_DIM);
        lClockQuote = Ui.numbers(Ui.medium, 28 * k, L_DIM);
        lightGradient = null;
        buttonPaint = Ui.text(Ui.semibold, 29 * k, INK);
        capsuleText = Ui.numbers(Ui.medium, 29 * k, 0xF2FFFFFF);
        tipPaint = Ui.text(Ui.semibold, 25 * k, 0xF2FFFFFF);
        tipPaint.setTextAlign(Paint.Align.CENTER);
        tipPaint.setShadowLayer(16 * k, 0, 1 * k, 0x99000000);
        // Quotes: a subtle detail, smaller and dimmer than the rest.
        quotePaint = Ui.numbers(Ui.regular, 21 * k, 0xB8FFFFFF);
        quotePaint.setShadowLayer(14 * k, 0, 1 * k, 0x99000000);
        staleQuote = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        staleQuote.setTextAlign(Paint.Align.RIGHT);
        rowTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        drawerLabel = Ui.text(Ui.medium, 23 * k, 0xEBFFFFFF);
        drawerLabel.setTextAlign(Paint.Align.CENTER);
        cardTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        cardText = Ui.text(Ui.medium, 29 * k, 0xF2FFFFFF);
        cardDim = Ui.numbers(Ui.medium, 23 * k, 0xADFFFFFF);
        cardDimRight = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        cardDimRight.setTextAlign(Paint.Align.RIGHT);
        bigTemp = Ui.numbers(Ui.light, 100 * k, 0xFFFFFFFF);
        hourPaint = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        hourPaint.setTextAlign(Paint.Align.CENTER);
        hourTemp = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        hourTemp.setTextAlign(Paint.Align.CENTER);
        pricePaint = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        pricePaint.setTextAlign(Paint.Align.RIGHT);
        changePaint = Ui.numbers(Ui.semibold, 21 * k, 0xFFFFFFFF);
        changePaint.setTextAlign(Paint.Align.CENTER);
        sheetTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
        sheetTitle.setTextAlign(Paint.Align.CENTER);
        gridLabel = new TextPaint(Ui.text(Ui.medium, 24 * k, 0x99FFFFFF));
        gridLabel.setTextAlign(Paint.Align.CENTER);
        iTitle = new TextPaint(Ui.text(Ui.displaySemibold, 60 * k, 0xFFFFFFFF));
        iMeta = Ui.text(Ui.medium, 27 * k, 0xB3FFFFFF);
        iBody = new TextPaint(Ui.text(Ui.regular, 29 * k, 0xE6FFFFFF));
        iLabel = Ui.text(Ui.semibold, 25 * k, 0xF2FFFFFF);
        iLabel.setTextAlign(Paint.Align.CENTER);
        iPill = Ui.numbers(Ui.semibold, 22 * k, 0xFFFFFFFF);
        infoTitle = infoOverview = null;
        if (infoPick != null) layoutInfo();
        sQuery = Ui.text(Ui.medium, 32 * k, 0xEBFFFFFF);
        sHint = Ui.text(Ui.medium, 32 * k, 0x80FFFFFF);
        sMeta = new TextPaint(Ui.text(Ui.medium, 22 * k, 0x99FFFFFF));
        sTitle = new TextPaint(Ui.text(Ui.semibold, 27 * k, 0xD9FFFFFF));
        sTitle.setLetterSpacing(0.01f);
        resultLabels = resultMetas = null;
        toastPaint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF));
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        shadowSoft.setMaskFilter(new BlurMaskFilter(12 * k, BlurMaskFilter.Blur.NORMAL));
        shadowDeep.setMaskFilter(new BlurMaskFilter(DEEP_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        halo.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * k);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.5f * k);
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();

        boolean tilesMoving = step(tileFocus, dt);
        if (reorderAnim) tilesMoving |= stepReorder(dt, up);
        boolean statusMoving = step(statusFocus, dt);
        boolean heroMoving = heroFocus.step(dt);
        boolean topMoving = step(topFocus, dt), cardsMoving = step(cardFocus, dt), wideMoving = step(cardWide, dt);
        boolean slideMoving = slide.step(dt), menuMoving = menu.step(dt) | step(menuFocus, dt);
        boolean infoMoving = info.step(dt) | step(infoFocus, dt);
        if (wideMoving) layoutCards();
        if (topMoving) status.invalidate();
        if (menuMoving || menuOpen || infoMoving || infoOpen) toastLayer.invalidate();
        heroMoving |= topMoving | cardsMoving | wideMoving | slideMoving | menuMoving;
        boolean drawerItemsMoving = step(drawerFocus, dt);
        boolean watchMoving = step(watchFocus, dt) | watchScroll.step(dt);
        boolean peekMoving = peek.step(dt);
        boolean gridMoving = step(gridFocus, dt) | scroll.step(dt) | step(resultFocus, dt) | fieldFocus.step(dt) | micFocus.step(dt) | clearFocus.step(dt) | sScroll.step(dt) | resultsLit.step(dt);
        boolean drawerMoving = drawer.step(dt);
        boolean sheetMoving = sheet.step(dt);
        boolean toastFading = tickToast(up);
        boolean heroFading = tickHeroFade(up);
        boolean bgFading = tickBackgroundFade(up);
        boolean nightMoving = tickNight(up);

        if (drawerMoving || sheetMoving || peekMoving || motionDirty) updateMotion();
        // Opening and closing the drawer only moves the cached layers: redrawing the glass on every frame made the TV
        // drop frames (measured: ~50 ms of GPU per frame). The app tiles have no glass; the top buttons fade out as
        // they rise; the drawer is drawn resting in place; the glass shelf is redrawn once the drawer stops, so the
        // glass shows the background where it ended up.
        if (tilesMoving || tilesDirty) tiles.invalidate();
        if (statusMoving || statusDirty || bgFading) status.invalidate();
        if (heroMoving || heroDirty || heroFading) heroLayer.invalidate();
        if (drawerItemsMoving || drawerDirty || bgFading) drawerLayer.invalidate();
        if (watchMoving || watchDirty || bgFading) watchLayer.invalidate();
        if (gridMoving || gridDirty || searchDirty) sheetLayer.invalidate();
        if (drawerMoving || peekMoving || bgFading) sky.invalidate();
        if (bgFading || (drawerWasMoving && !drawerMoving) || (peekWasMoving && !peekMoving)) platter.invalidate();
        drawerWasMoving = drawerMoving;
        peekWasMoving = peekMoving;
        tilesDirty = statusDirty = heroDirty = drawerDirty = gridDirty = searchDirty = motionDirty = watchDirty = false;
        if (updateClock()) {
            layoutStatus();
            status.invalidate();
            if (hero != null && hero.clock) {
                refreshHero(false);
                heroLayer.invalidate();
            }
            if (!picksOn()) heroLayer.invalidate();
        }

        // Busy: something is moving, or the sheet or the drawer is open.
        boolean busy = tilesMoving || statusMoving || heroMoving || drawerItemsMoving || gridMoving || drawerMoving
                || sheetMoving || toastFading || heroFading || bgFading || nightMoving || sheetOpen || searchOpen || sheet.value > 0.001f
                || drawerOpen || drawer.value > 0.001f || toast != null || !toasts.isEmpty() || menuOpen || menu.value > 0.001f
                || infoOpen || info.value > 0.001f || peekMoving || watchMoving || peekOpen || peek.value > 0.001f;
        idle = !busy;
        if (busy) Choreographer.getInstance().postFrameCallback(this);
        else Choreographer.getInstance().postFrameCallbackDelayed(this, 1000);
    }

    private void wake() {
        if (!running || !idle) return;
        idle = false;
        lastNanos = 0;
        Choreographer.getInstance().removeFrameCallback(this);
        Choreographer.getInstance().postFrameCallback(this);
    }

    private static boolean step(Spring[] springs, float dt) {
        boolean moving = false;
        for (Spring s : springs) moving |= s.step(dt);
        return moving;
    }

    /** Drawer and sheet: the layers' positions and opacity (without redrawing their content). */
    private void updateMotion() {
        float d = drawer.value, dc = Ui.clamp(d), s = Ui.clamp(sheet.value), p = peek.value, pc = Ui.clamp(p);
        float home = 1 - s, top = home * (1 - smoothstep(0, 0.32f, dc));
        float shift = SHIFT * k;
        float lift = -(d * shift + p * W_LIFT * k);   // the row's lift and the drawer's add up while one gives way to the other
        show(heroLayer, top, lift);
        show(status, top, lift);
        show(platter, home, lift);
        show(tiles, home, lift);
        float da = home * smoothstep(0.1f, 0.6f, dc);
        drawerLayer.setTranslationY((1 - d) * shift);
        drawerLayer.setAlpha(da);
        drawerLayer.setVisibility(da > 0.002f ? VISIBLE : INVISIBLE);
        // The row rides with the shelf (resting below the screen) and fades out as the drawer takes its place.
        float wa = home * pc * (1 - smoothstep(0, 0.5f, dc));
        watchLayer.setTranslationY(lift + W_LIFT * k);
        watchLayer.setAlpha(wa);
        watchLayer.setVisibility(wa > 0.002f ? VISIBLE : INVISIBLE);
        sheetLayer.setVisibility(s > 0.001f || sheetOpen || searchOpen ? VISIBLE : INVISIBLE);
        sheetLayer.setAlpha(s);
        float scale = 1.04f - 0.04f * s;
        sheetLayer.setScaleX(scale);
        sheetLayer.setScaleY(scale);
    }

    private static void show(View v, float alpha, float translationY) {
        v.setAlpha(alpha);
        v.setTranslationY(translationY);
        v.setVisibility(alpha > 0.002f ? VISIBLE : INVISIBLE);
    }

    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute || clockPaint == null) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        dateText = Ui.date(now);
        return true;
    }

    private void updatePalette() {
        HomeData.Weather wx = data.weather;
        Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
        scene.applyPalette();
    }

    // MARK: - Recommendations: hero and background

    private Recs.Pick currentPick() {
        return picks.isEmpty() ? null : picks.get(Math.min(pickIndex, picks.size() - 1));
    }

    /** This hour's picks: the cards and the background image (called when Recs notifies and every 15 s). */
    private void updatePicks() {
        if (labelPaint == null) return;
        updateCards();
    }

    private static boolean samePicks(List<Recs.Pick> a, List<Recs.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Recs.key(a.get(i)).equals(Recs.key(b.get(i)))) return false;
        return true;
    }

    /**
     * Rebuilds the hero. With `animate`, a different title slides in while the previous one slides out; details that
     * arrive later (genre, runtime) just appear.
     */
    private void refreshHero(boolean animate) {
        Hero next = makeHero();
        if (next.sameText(hero) && hero.dots == next.dots && hero.dot == next.dot && hero.button == next.button) return;
        boolean other = hero != null && (hero.clock != next.clock || !hero.title.equals(next.title));
        if (animate && other) {
            heroFrom = hero;
            heroFadeAt = SystemClock.uptimeMillis();
        }
        boolean layoutChanged = hero == null || hero.clock != next.clock;
        hero = next;
        heroDirty = true;
        if (layoutChanged) layoutStatus();
    }

    private Hero makeHero() {
        Hero h = new Hero();
        Recs.Pick p = currentPick();
        if (p == null) {
            h.clock = true;
            h.label = Ui.date(System.currentTimeMillis()).toUpperCase(Ui.locale());
            return h;
        }
        h.label = Recs.periodLabel();
        h.title = p.title == null ? "" : p.title;
        h.meta = p.meta == null ? "" : p.meta;
        h.button = p.pkg != null;
        h.dots = picks.size();
        h.dot = pickIndex;
        h.titleLayout = StaticLayout.Builder.obtain(h.title, 0, h.title.length(), titlePaint, Math.max(1, Math.round(HERO_TITLE_W * k)))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0, 0.94f)
                .setIncludePad(false)
                .build();
        return h;
    }

    /** ←/→ on the hero: the next (or previous) of this hour's picks. */
    private boolean browse(int dir) {
        if (picks.size() < 2) return false;
        int next = (pickIndex + dir + picks.size()) % picks.size();
        pickIndex = next;
        heroSlide = dir;
        refreshHero(true);
        Backdrop b = recs.backdrop(currentPick());
        if (b != null) showBackdrop(b);
        spoken.focusMoved();
        return true;
    }

    /** Swaps the background with a cross-fade (from one image to another, from the gradient to an image, or back). */
    private void showBackdrop(Backdrop b) {
        if (b == bgTo) return;
        long up = SystemClock.uptimeMillis();
        // In the middle of another swap, start from whichever is more visible.
        if (bgFadeAt == 0 || fadeEase(bgFadeAt, up) >= 0.5f) bgFrom = bgTo;
        bgTo = b;
        bgFadeAt = up;
        nightPair = false;
        if (b == null || glassOf(b) == null) return;
        if (bgFrom == null || glassOf(bgFrom) == null) {
            scene.setFrost(frostOf(b));
            scene.setGlassImage(glassOf(b));
        } else {
            scene.setGlassImages(glassOf(bgFrom), glassOf(b));
        }
    }

    /** What the glass shows: the photo lightened (by day), darkened (at night) or blended in between. */
    private int[] glassOf(Backdrop b) {
        if (b.nightGlass() == null || night <= 0.001f) return b.lightGlass();
        if (night >= 0.999f) return b.nightGlass();
        // During the transition, the blend (only for the current background; a photo fading in uses the nearer side).
        if (b != bgTo) return night < 0.5f ? b.lightGlass() : b.nightGlass();
        int[] day = b.lightGlass(), dark = b.nightGlass();
        for (int i = 0; i < glassNight.length; i++) glassNight[i] = mix(day[i], dark[i], night);
        return glassNight;
    }

    /** No photo: the glass reads the background's own gray (not Scene's default aurora, which isn't on screen). */
    private void neutralGlass() {
        if (!scene.shaded()) {   // Android 8 to 12: a gray image would be one more full-area pass for the flat look
            scene.clearFrost();
            scene.clearGlass();
            nightPair = false;
            return;
        }
        int color = blend(0xFFEBEBF0, 0xFF141416, night);
        java.util.Arrays.fill(neutralFrostPx, color);
        java.util.Arrays.fill(neutralGlassPx, color);
        scene.setFrost(neutralFrostPx);
        scene.setGlassImage(neutralGlassPx);
        nightPair = false;
    }

    /** What the frosted glass shows (drawer, toasts): the blurred photo, darkened at night. */
    private int[] frostOf(Backdrop b) {
        if (b.nightFrost() == null || night <= 0.001f) return b.frost;
        if (night >= 0.999f) return b.nightFrost();
        if (b != bgTo) return night < 0.5f ? b.frost : b.nightFrost();
        int[] dark = b.nightFrost();
        for (int i = 0; i < frostNight.length; i++) frostNight[i] = mix(b.frost[i], dark[i], night);
        return frostNight;
    }

    // MARK: - Night look

    /** Day or night right now (from the sun at your location); `animate`: with the transition. */
    private void checkNight(boolean animate) {
        boolean want = data.isNight(System.currentTimeMillis());
        if (want == nightTarget) return;
        nightTarget = want;
        if (animate && running) {
            nightFrom = night;
            nightAt = SystemClock.uptimeMillis();
        } else {
            night = want ? 1 : 0;
            nightAt = 0;
            nightChanged();
        }
    }

    /** Advances the day ⇄ night transition; true while it runs. */
    private boolean tickNight(long up) {
        if (nightAt == 0) return false;
        float t = Ui.clamp((up - nightAt) / NIGHT_MS), to = nightTarget ? 1 : 0;
        night = nightFrom + (to - nightFrom) * t * t * (3 - 2 * t);
        if (t >= 1) {
            night = to;
            nightAt = 0;
        }
        nightChanged();
        return true;
    }

    /** Redraws for the current night value (the glass now reads the lightened, blended or darkened photo). */
    private void nightChanged() {
        if (bgFadeAt == 0 && bgTo != null && bgTo.lightGlass() != null) {
            scene.setFrost(frostOf(bgTo));
            if (nightAt != 0 && bgTo.nightGlass() != null) {
                if (!nightPair) {
                    scene.setGlassImages(bgTo.lightGlass(), bgTo.nightGlass());
                    nightPair = true;
                }
                scene.setGlassMix(night);
            } else {
                scene.setGlassImage(glassOf(bgTo));
                nightPair = false;
            }
        } else if (bgFadeAt == 0 && bgTo == null) {
            neutralGlass();
        }
        heroDirty = statusDirty = tilesDirty = drawerDirty = true;
        sky.invalidate();
        platter.invalidate();
    }

    /** How dark it is: 0 = day, 1 = night (in between during the transition). */
    private float dark() {
        return night;
    }

    /** The day color or the night color, depending on the night value (blended during the transition). */
    private int tone(int onLight, int onDark) {
        return blend(onLight, onDark, dark());
    }

    private float tone(float onLight, float onDark) {
        return onLight + (onDark - onLight) * dark();
    }

    /** Text colors for the current night value (dark by day, white at night). */
    private void applyTone() {
        lTitle.setColor(tone(L_INK, WHITE));
        lTab.setColor(tone(L_DIM, 0xFF8E8E93));
        lTabOn.setColor(tone(L_INK, WHITE));
        lCardTitle.setColor(tone(L_INK, WHITE));
        lCardMeta.setColor(tone(L_DIM, 0xFF98989D));
        lQuote.setColor(tone(L_FAINT, 0xB8FFFFFF));
        lTip.setColor(tone(L_INK, WHITE));
        lDate.setColor(tone(L_DIM, 0xFF98989D));
        lClock.setColor(tone(L_INK, WHITE));
        lWeather.setColor(tone(L_DIM, 0xFF98989D));
        lClockQuote.setColor(tone(L_DIM, 0xFF98989D));
        staleQuote.setColor(tone(L_FAINT, 0x99FFFFFF));
    }

    private boolean tickBackgroundFade(long up) {
        if (bgFadeAt == 0) return false;
        float e = fadeEase(bgFadeAt, up);
        if (bgTo != null && bgFrom != null && glassOf(bgFrom) != null && glassOf(bgTo) != null) {
            if (drawerLayer.getVisibility() == VISIBLE || toast != null) {
                int[] from = frostOf(bgFrom), to = frostOf(bgTo);
                for (int i = 0; i < frostMix.length; i++) frostMix[i] = mix(from[i], to[i], e);
                scene.setFrost(frostMix);
            }
            scene.setGlassMix(e);
        }
        if (e >= 1) {
            bgFadeAt = 0;
            bgFrom = null;
            if (bgTo == null) {
                neutralGlass();
            } else {
                scene.setFrost(frostOf(bgTo));
                if (glassOf(bgTo) != null) scene.setGlassImage(glassOf(bgTo));
            }
        }
        return true;
    }

    private boolean tickHeroFade(long up) {
        if (heroFadeAt == 0) return false;
        if (fadeEase(heroFadeAt, up) >= 1) {
            heroFadeAt = 0;
            heroFrom = null;
        }
        return true;
    }

    private float fadeEase(long start, long up) {
        return fadeEase(start, up, FADE_MS);
    }

    private static float fadeEase(long start, long up, float duration) {
        float t = Ui.clamp((up - start) / duration);
        return t * t * (3 - 2 * t);
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // MARK: - Background

    private void drawSky(Canvas c) {
        drawLightSky(c);
    }

    /** Blends a color on top (the drawer's veil) as part of drawing the image, without another layer. */
    private android.graphics.ColorFilter veil(float amount, int color) {
        if (amount <= 0.002f) return null;
        amount = Math.round(amount * 255) / 255f;
        if (amount == veilAmount && color == veilColor) return veilFilter;
        veilAmount = amount;
        veilColor = color;
        float keep = 1 - amount;
        return veilFilter = new android.graphics.ColorMatrixColorFilter(new float[] {
            keep, 0, 0, 0, ((color >> 16) & 255) * amount,
            0, keep, 0, 0, ((color >> 8) & 255) * amount,
            0, 0, keep, 0, (color & 255) * amount,
            0, 0, 0, 1, 0});
    }

    private void drawImage(Canvas c, Bitmap image, float alpha) {
        if (image == null || alpha <= 0.004f) return;
        imagePaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(image, null, screen, imagePaint);
    }

    // MARK: - Hero

    private static void text(Canvas c, String s, float x, float y, Paint p, int color, float alpha) {
        if (s == null || s.isEmpty()) return;
        p.setColor(Ui.alpha(color, alpha));
        c.drawText(s, x, y, p);
    }

    // MARK: - Corner capsule: time and weather (when the hero shows a recommendation) and icon-only buttons

    /** Live TV, the inputs with a device on, All Apps and Settings. */
    private static List<HomeApps.Item> statusList(Context c) {
        List<HomeApps.Item> list = new ArrayList<>();
        list.addAll(HomeApps.shortcuts(c));
        list.add(HomeApps.settings());
        return list;
    }

    private void setStatusItems(List<HomeApps.Item> list) {
        boolean same = list.size() == statusItems.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(statusItems.get(i).key);
        String focusedKey = statusIndex < statusItems.size() ? statusItems.get(statusIndex).key : null;
        statusItems = list;
        if (!same || statusFocus.length != list.size()) {
            statusFocus = new Spring[list.size()];
            statusRects = new RectF[list.size()];
            int index = Math.min(statusIndex, Math.max(0, list.size() - 1));
            for (int i = 0; i < statusFocus.length; i++) {
                statusFocus[i] = new Spring(0.26f, 0.85f, 0);
                statusRects[i] = new RectF();
                if (list.get(i).key.equals(focusedKey)) index = i;   // a device came on: keep focus on the same button
            }
            statusIndex = index;
            if (zone == Z_STATUS && list.isEmpty()) zone = Z_SHELF;
            aim(statusFocus, zone == Z_STATUS ? statusIndex : -1, true);   // only the capsule's springs
            statusDirty = true;
        }
        layoutStatus();
    }

    private boolean showInfo() {
        return hero != null && !hero.clock;
    }

    private void layoutStatus() {
        if (capsuleText == null) return;
        int n = statusItems.size();
        float b = BUTTON * k, gap = 4 * k, pad = 6 * k;
        float info = 0;
        if (showInfo()) {
            info = 26 * k + capsuleText.measureText(clockText);
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp) && Prefs.weather()) {
                info += 22 * k + Symbols.width(getContext(), Symbols.weather(wx.code, wx.isDay), 24 * k) + 8 * k
                        + capsuleText.measureText(HomeData.degrees(wx.temp));
            }
            info += 22 * k;
        }
        capsuleInfoW = info;
        float buttons = n * b + Math.max(0, n - 1) * gap;
        float right = CONTENT_R * k, top = CAPSULE_TOP * k;
        float left = right - pad - buttons - (info > 0 ? info + 12 * k : pad);
        capsuleRect.set(left, top, right, top + CAPSULE_H * k);
        float x = right - pad - buttons, cy = capsuleRect.centerY();
        for (int i = 0; i < n; i++) {
            statusRects[i].set(x, cy - b / 2, x + b, cy + b / 2);
            x += b + gap;
        }
        statusDirty = true;
    }

    // MARK: - Quotes and shelf

    private void setShelf(List<HomeApps.Item> list) {
        wake();
        cancelReorder(true);    // the list changed under the move (an app installed or removed)
        tileShaders = new LinearGradient[0];
        boolean same = list.size() == shelf.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(shelf.get(i).key);
        shelf = list;
        if (!same || tileFocus.length != list.size()) {
            tileFocus = new Spring[list.size()];
            tileSlide = new Spring[list.size()];
            tileRects = new RectF[list.size()];
            for (int i = 0; i < tileFocus.length; i++) {
                tileFocus[i] = new Spring(0.34f, 0.74f, 0);
                tileSlide[i] = new Spring(0.32f, 0.86f, 0);
                tileRects[i] = new RectF();
            }
            shelfIndex = Math.min(shelfIndex, Math.max(0, list.size() - 1));
            retarget(true);
            layoutShelf();
        }
        tilesDirty = true;
        platter.invalidate();
    }

    private void layoutShelf() {
        int n = Math.min(shelf.size(), shelfVisible);
        float pad = SHELF_PAD * k, gap = TILE_GAP * k;
        float full = (CONTENT_R - MARGIN) * k;
        // Tiles in the TV banners' own 16:9, so an app's art is never stretched; never taller than L_TILE_H. The glass
        // keeps its bottom edge, so with more apps it only gets lower.
        float tw = Math.min(L_TILE_H * 16f / 9 * k, (full - Math.max(0, n - 1) * gap) / Math.max(1, n));
        float th = tw * 9f / 16;
        float width = n * tw + Math.max(0, n - 1) * gap, left = (w - width) / 2f;
        float top = (L_PLATTER_TOP + L_TILE_H) * k - th;
        platterRect.set(left - pad, top, left + width + pad, top + th + 2 * pad);
        snap(platterRect);
        shelfFirst = Math.max(0, Math.min(shelfFirst, shelf.size() - n));
        float x = left - shelfFirst * (tw + gap), y = top + pad;
        for (int i = 0; i < shelf.size(); i++) {
            tileRects[i].set(x, y, x + tw, y + th);
            snap(tileRects[i]);
            x += tw + gap;
        }
        crispLogos.clear();
    }

    /** Scrolls the shelf, one app at a time, so the focused app is shown: the tiles slide there (unless `snap`). */
    private void revealShelf(boolean snap) {
        int n = Math.min(shelf.size(), shelfVisible);
        int first = Math.max(0, Math.min(Math.max(shelfFirst, shelfIndex - n + 1), Math.min(shelfIndex, shelf.size() - n)));
        if (first == shelfFirst || tileRects.length == 0) return;
        float dx = (shelfFirst - first) * (tileRects[0].width() + TILE_GAP * k);
        for (int i = 0; i < tileRects.length; i++) {
            tileRects[i].offset(dx, 0);
            if (!snap && i < tileSlide.length) tileSlide[i].value -= dx;
        }
        shelfFirst = first;
        tilesDirty = true;
        if (snap) return;
        reorderAnim = true;   // the same slides as moving an app
        wake();
    }

    /** Shelf layer: the glass bar and its shadow (it only changes with the background). */
    private void drawPlatter(Canvas c) {
        if (shelf.isEmpty()) return;
        float radius = (TILE_RADIUS + SHELF_PAD) * k;
        tmp.set(platterRect);
        tmp.offset(0, 18 * k);
        shadow.setColor(tone(0x1F000000, 0x52000000));
        shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
        // White glass by day; smoked glass at night.
        scene.liquid(c, platterRect, radius, tone(0xFFFFFFFF, SMOKE), tone(0.42f, 0.12f), tone(0.03f, 0.08f),
                tone(30, 34) * k, tone(22, 28) * k, tone(0.65f, 0.95f), 1);
    }

    /** The quotes row's text and sizes, for one set of coins, prices and text size. */
    private final class Quotes {
        final int[] coins;
        final double[] prices;
        final float size, cap, halfCap;
        final String[] values;
        final float[] glyphs, widths;

        Quotes(int[] coins, double[] all, Paint text) {
            this.coins = coins;
            size = text.getTextSize();
            cap = Ui.cap(text);
            halfCap = Ui.halfCap(text);
            int n = coins.length;
            prices = new double[n];
            values = new String[n];
            glyphs = new float[n];
            widths = new float[n];
            for (int i = 0; i < n; i++) {
                prices[i] = all[coins[i]];
                values[i] = HomeData.compact(prices[i]);
                glyphs[i] = Glyphs.width(getContext(), HomeData.COINS[coins[i]], cap);
                widths[i] = text.measureText(values[i]);
            }
        }

        boolean matches(int[] now, double[] all, float textSize) {
            if (now != coins || textSize != size) return false;
            for (int i = 0; i < coins.length; i++) if (Double.compare(all[coins[i]], prices[i]) != 0) return false;
            return true;
        }
    }

    private Quotes quotes;

    /** Coin logo + price in the configured currency, in a row at the right end of the tab bar. */
    private void drawQuotes(Canvas c) {
        boolean clock = !picksOn();
        // At the right margin, under the round buttons: the shelf's glass is narrower and centred with few apps.
        float right = CONTENT_R * k;
        applyTone();
        Paint text = clock ? lClockQuote : lQuote;
        // The corner buttons' names appear in this row: the quotes fade out while one of them has focus.
        float fade = 1;
        for (int i = TABS; i < TABS + lightButtons.size() && !clock; i++) fade = Math.min(fade, 1 - Ui.clamp(topFocus[i].value));
        if (fade <= 0.01f) return;
        int textColor = text.getColor();
        text.setColor(Ui.alpha(textColor, fade));
        // One read of the list, so n always matches the coins drawn below.
        int[] coins = HomeData.active();
        int n = coins.length;
        if (n == 0) { text.setColor(textColor); return; }
        // Measured once per set of prices: this row is redrawn on every frame of the cards' focus animation, and
        // measuring text there cost ~3 ms a frame on a Mi Box 3.
        Quotes q = quotes;
        if (q == null || !q.matches(coins, data.prices, text.getTextSize())) {
            q = quotes = new Quotes(coins, data.prices, text);
        }
        // On the tab row, at the right.
        float base = clock ? L_QUOTES_BASE * k : (L_TABBAR_TOP + L_TABBAR_H / 2) * k + q.halfCap;
        float cap = q.cap, gap = (clock ? 8 : 6) * k, spacing = (clock ? 32 : 24) * k;
        float total = spacing * (n - 1);
        for (int i = 0; i < n; i++) total += q.glyphs[i] + gap + q.widths[i];
        float x = clock ? MARGIN * k : right - total;
        int coinColor = clock ? text.getColor() : Ui.alpha(tone(0xB38E8E93, 0x8CFFFFFF), fade);
        for (int i = 0; i < n; i++) {
            Glyphs.coin(getContext(), c, HomeData.COINS[coins[i]], x + q.glyphs[i] / 2, base - cap / 2, cap, coinColor);
            x += q.glyphs[i] + gap;
            c.drawText(q.values[i], x, base, text);
            x += q.widths[i] + spacing;
        }
        text.setColor(textColor);
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        if (stale.isEmpty()) return;
        if (clock) c.drawText(stale, x - spacing + 24 * k + staleQuote.measureText(stale), base, staleQuote);
        else c.drawText(stale, right - total - 24 * k, base, staleQuote);
    }

    private void drawTiles(Canvas c) {
        // The focused one last: it grows over its neighbors without being covered by them.
        int top = -1;
        float most = 0.02f;
        for (int i = 0; i < shelf.size(); i++) {
            if (tileFocus[i].value > most) {
                most = tileFocus[i].value;
                top = i;
            }
        }
        if (liftIndex >= 0 && liftIndex < shelf.size() && moveLift.value > 0.02f) top = liftIndex;
        int last = Math.min(shelf.size(), shelfFirst + shelfVisible);
        // Scrolling, tiles slide in and out through the glass's sides (only those that show any part are drawn). Only
        // then: on a shelf that fits, the clip would cut the end tiles' shadows in a straight line.
        boolean scrolls = shelf.size() > shelfVisible;
        if (scrolls) {
            c.save();
            c.clipRect(platterRect.left, 0, platterRect.right, h);
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < shelf.size(); i++) {
                if ((pass == 0) == (i == top)) continue;
                float slid = i < tileSlide.length ? tileSlide[i].value : 0;
                if (tileRects[i].right + slid <= platterRect.left || tileRects[i].left + slid >= platterRect.right) continue;
                // Moving apps: the tile slides from where it was and the lifted one wiggles (only then: no extra work
                // otherwise).
                float dx = i < tileSlide.length ? tileSlide[i].value : 0;
                float angle = i == liftIndex ? wiggle * moveLift.value : 0;
                if (dx == 0 && angle == 0) {
                    drawShelfTile(c, i);
                    continue;
                }
                c.save();
                c.translate(dx, 0);
                c.rotate(angle, tileRects[i].centerX(), tileRects[i].centerY());
                drawShelfTile(c, i);
                c.restore();
            }
        }
        if (scrolls) c.restore();
        // More apps than fit: a small arrow inside the glass's edge on each side that has more, where no tile reaches
        // even focused (inside the screen's safe area).
        float cy = platterRect.centerY(), size = 28 * k, inset = 14 * k;
        int ink = tone(0x99000000, 0xCCFFFFFF);
        if (last < shelf.size()) Symbols.draw(getContext(), c, "chevron_right", platterRect.right - inset, cy, size, ink);
        if (shelfFirst > 0) {
            c.save();
            c.scale(-1, 1, platterRect.left + inset, cy);
            Symbols.draw(getContext(), c, "chevron_right", platterRect.left + inset, cy, size, ink);
            c.restore();
        }
    }

    /**
     * App icon like the ones in the tvOS 27 Dock: the brand color edge to edge (a soft gradient) with the logo.
     * Focused: it grows 20% from the center, with a deep shadow (it "rises" toward the viewer) and a subtle glow at the
     * top. No border and no reflection, as Apple does it.
     */
    private void drawShelfTile(Canvas c, int i) {
        HomeApps.Item it = shelf.get(i);
        // Lifted to be moved: a little bigger than focused, with a deeper shadow.
        float l = i == liftIndex ? Math.max(0, moveLift.value) : 0;
        float f = tileFocus[i].value, fc = Ui.clamp(f), s = 1 + FOCUS_GROW * f + LIFT_GROW * l;
        RectF base = tileRects[i];
        float bw = base.width(), bh = base.height(), r0 = TILE_RADIUS * k;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        // Shadow at the resting icon size, enlarged by the transform (so the blur stays cached): short at rest, deep
        // and lower when focused.
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (4 + 14 * f + 6 * l) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.3f) + tone(0.18f, 0.3f) * fc + 0.14f * Ui.clamp(l)));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        int[] style = TILE_STYLE.get(it.key);
        if (it.logo != null && style != null) {
            if (tileShaders.length != shelf.size() || tileShaderH != bh) {
                tileShaders = new LinearGradient[shelf.size()];
                tileShaderH = bh;
                tileGlow = new LinearGradient(0, -bh / 2, 0, 0, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            }
            if (tileShaders[i] == null) {
                tileShaders[i] = new LinearGradient(0, -bh / 2, 0, bh / 2, style[0], style[1], Shader.TileMode.CLAMP);
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            tileFill.setShader(tileShaders[i]);
            tileFill.setAlpha(255);
            c.drawRoundRect(tmp, r0, r0, tileFill);
            if (fc > 0.01f) {
                tileFill.setShader(tileGlow);
                tileFill.setAlpha(Math.round(255 * fc));
                c.drawRoundRect(tmp, r0, r0, tileFill);
                tileFill.setAlpha(255);
            }
            tileFill.setShader(null);
            c.restore();
            drawLogo(c, it, cell, s, style[2]);
        } else if (it.logo != null) {
            tileFill.setShader(null);
            tileFill.setColor(0xFF161618);
            c.drawRoundRect(cell, r, r, tileFill);
            drawLogo(c, it, cell, s, 0);
        } else {
            if (it.tile != null) roundImage(c, it.tile, it.tileShader, cell, r);
        }
        drawFocusRing(c, cell, r, fc);
    }

    /**
     * `image` stretched over `cell`, with corners of radius `r`: the shader of an anti-aliased rounded rectangle. A
     * clipPath there isn't anti-aliased on some GPUs (stair-stepped corners on a MediaTek TV), and on Android 8 to 11 it
     * cost a mask on every frame of a focus animation (measured on a Mi Box 3: most of a 49 ms frame). `shader` is the
     * image's own, made with it (HomeApps.Item, Backdrop) and dropped with it; the canvas does the scaling, so drawing
     * changes nothing in it and allocates nothing.
     */
    private void roundImage(Canvas c, Bitmap image, BitmapShader shader, RectF cell, float r) {
        float sx = cell.width() / image.getWidth(), sy = cell.height() / image.getHeight();
        c.save();
        c.translate(cell.left, cell.top);
        c.scale(sx, sy);
        imageFill.setShader(shader);
        c.drawRoundRect(0, 0, image.getWidth(), image.getHeight(), r / sx, r / sy, imageFill);
        imageFill.setShader(null);
        c.restore();
    }

    /** Like roundImage, but keeps the image's proportions and crops it to the cell (centered): a card of any width. */
    private void roundImageCover(Canvas c, Bitmap image, BitmapShader shader, RectF cell, float r) {
        float s = Math.max(cell.width() / image.getWidth(), cell.height() / image.getHeight());
        shaderMatrix.setScale(s, s);
        shaderMatrix.postTranslate(cell.left - (image.getWidth() * s - cell.width()) / 2,
                cell.top - (image.getHeight() * s - cell.height()) / 2);
        shader.setLocalMatrix(shaderMatrix);
        imageFill.setShader(shader);
        c.drawRoundRect(cell, r, r, imageFill);
        imageFill.setShader(null);
        shader.setLocalMatrix(null);
    }

    /**
     * Logo at its "optical" size (all with the same amount of ink, plus each one's own adjustment, never past 80% × 62%
     * of the tile), centered and already filtered down to the focused tile size and to the resting one: focused or at
     * rest it is drawn pixel for pixel, without resampling (on a 720p UI, shrinking at draw time made it look jagged).
     */
    private void drawLogo(Canvas c, HomeApps.Item it, RectF box, float s, int mode) {
        boolean rest = s < 1 + FOCUS_GROW / 2;
        Bitmap logo = crispLogo(it, mode == 2 && it.logoDark != null, rest);
        if (logo == null) return;
        float scale = rest ? s : s / (1 + FOCUS_GROW);
        float dw = logo.getWidth() * scale, dh = logo.getHeight() * scale;
        float left = box.centerX() - dw / 2, top = box.centerY() - dh / 2;
        if (rest ? Math.abs(scale - 1) < 0.002f : scale > 0.998f) {
            left = Math.round(left);
            top = Math.round(top);
        }
        tmp.set(left, top, left + dw, top + dh);
        bitmapPaint.setColorFilter(mode == 1 ? whiteLogo : null);
        c.drawBitmap(logo, null, tmp, bitmapPaint);
        bitmapPaint.setColorFilter(null);
    }

    private Bitmap crispLogo(HomeApps.Item it, boolean dark, boolean rest) {
        Bitmap src = dark ? it.logoDark : it.logo;
        if (tileRects.length == 0) return src;
        String key = it.key + "@" + System.identityHashCode(src) + "@" + Math.round(tileRects[0].width()) + (rest ? "@rest" : "");
        Bitmap cached = crispLogos.get(key);
        if (cached != null) return cached;
        float bw = tileRects[0].width() * (1 + FOCUS_GROW), bh = tileRects[0].height() * (1 + FOCUS_GROW);
        float lw = src.getWidth(), lh = src.getHeight();
        float scale = (float) Math.sqrt(bw * bh * 0.085f / (lw * lh * Math.max(0.08f, it.logoInk))) * it.logoScale;
        scale = Math.min(scale, Math.min(bw * 0.76f / lw, bh * 0.58f / lh));
        if (rest) scale /= 1 + FOCUS_GROW;
        Bitmap out = Ui.downscale(src, Math.round(lw * scale), Math.round(lh * scale));
        crispLogos.put(key, out);
        return out;
    }

    // MARK: - Bottom drawer

    private static List<HomeApps.Item> drawerList(Context c) {
        List<HomeApps.Item> list = HomeApps.allInputs(c);
        list.addAll(HomeApps.drawerShortcuts(c));
        return list;
    }

    private void setDrawerItems(List<HomeApps.Item> list) {
        int inputs = 0;
        while (inputs < list.size() && (list.get(inputs).kind == HomeApps.INPUT || list.get(inputs).kind == HomeApps.LIVE_TV)) inputs++;
        boolean same = list.size() == drawerItems.size();
        for (int i = 0; same && i < list.size(); i++) {
            same = list.get(i).key.equals(drawerItems.get(i).key) && list.get(i).connected == drawerItems.get(i).connected
                    && String.valueOf(list.get(i).label).equals(String.valueOf(drawerItems.get(i).label));   // "HDMI 1" -> "PlayStation 5"
        }
        drawerItems = list;
        drawerInputs = inputs;
        if (drawerFocus.length != list.size()) {
            drawerFocus = new Spring[list.size()];
            drawerRects = new RectF[list.size()];
            for (int i = 0; i < drawerFocus.length; i++) {
                drawerFocus[i] = new Spring(0.28f, 0.85f, 0);
                drawerRects[i] = new RectF();
            }
            drawerIndex = Math.min(drawerIndex, Math.max(0, list.size() - 1));
            retarget(true);
        }
        layoutDrawer();
        if (!same) drawerDirty = true;
    }

    private void layoutDrawer() {
        int n = drawerItems.size();
        float left = MARGIN * k, right = CONTENT_R * k;
        float gap = 18 * k, groupGap = 56 * k;
        int groups = drawerInputs > 0 && drawerInputs < n ? 2 : 1;
        float tw = Math.min(230 * k, (right - left - Math.max(0, n - groups) * gap - (groups - 1) * groupGap) / Math.max(1, n));
        float x = left, top = ROW_TOP * k;
        for (int i = 0; i < n; i++) {
            if (i == drawerInputs && i > 0) x += groupGap - gap;
            drawerRects[i].set(x, top, x + tw, top + ROW_H * k);
            snap(drawerRects[i]);
            x += tw + gap;
        }
        float cardsTop = CARDS_TOP * k, split = left + (right - left) * 0.585f;
        boolean wx = Prefs.weather(), qt = Prefs.quotes() && HomeData.active().length > 0;
        // A card that is off gives its room to the other one.
        weatherCard.set(left, cardsTop, qt ? split - 16 * k : right, cardsTop + CARDS_H * k);
        quotesCard.set(wx ? split + 16 * k : left, cardsTop, right, cardsTop + CARDS_H * k);
        snap(weatherCard);
        snap(quotesCard);
        // The symbols at their sizes at rest and focused: drawing them never scales one.
        for (HomeApps.Item it : drawerItems) {
            Symbols.warm(getContext(), it.symbol, 34 * k);
            Symbols.warm(getContext(), it.symbol, 34 * k * 1.07f);
        }
        Symbols.warmWeather(getContext(), 52 * k);
        Symbols.warmWeather(getContext(), 30 * k);
        Symbols.warm(getContext(), "sunrise", 22 * k);
        Symbols.warm(getContext(), "sunset", 22 * k);
        drawerDirty = true;
    }

    private void openDrawer() {
        if (drawerItems.isEmpty()) return;
        drawerOpen = true;
        drawer.target = 1;
        float x = zone == Z_WATCH && watchIndex < watchRects.length ? watchRects[watchIndex].centerX() + watchScroll.value
                : shelf.isEmpty() ? w / 2f : tileRects[shelfIndex].centerX();
        peekOpen = false;   // the row gives way to the drawer
        peek.target = 0;
        for (Spring f : watchFocus) f.snap(0);
        zone = Z_DRAWER;
        drawerIndex = nearest(drawerRects, x);
        for (int i = 0; i < drawerFocus.length; i++) drawerFocus[i].snap(i == drawerIndex ? 1 : 0);
        drawerDirty = true;
    }

    private void closeDrawer() {
        drawerOpen = false;
        drawer.target = 0;
        zone = Z_SHELF;
        for (Spring f : drawerFocus) f.snap(0);   // the drawer goes away: its focus doesn't need to animate
        if (!shelf.isEmpty() && drawerIndex < drawerRects.length) shelfIndex = nearest(tileRects, drawerRects[drawerIndex].centerX());
    }

    /** Color for the drawer's text and symbols: dark by day, white at night. */
    private int drawerInk(float alpha) {
        return Ui.alpha(tone(L_INK, 0xFFFFFFFF), alpha);
    }

    /** Glass for a drawer card or button: white by day, smoked at night (both during the transition). */
    private void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop) {
        float dk = dark();
        if (dk < 0.999f) {
            tmp.set(r);
            tmp.offset(0, drop);
            shadow.setColor(Ui.alpha(shadowColor, 1 - dk));
            shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
            scene.glass(c, r, radius, 0xFFFFFFFF, lightTint, 0, 0, 0.02f, 1, 1 - dk);
        }
        if (dk > 0.001f) scene.glass(c, r, radius, SMOKE, 0.4f, 0, 0, 0.02f, 1, dk);
    }

    private void drawerCard(Canvas c, RectF r) {
        drawerGlass(c, r, 30 * k, 0.72f, 0x1A000000, 10 * k);
    }

    private void drawDrawer(Canvas c) {
        if (drawerItems.isEmpty()) return;
        rowTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardText.setColor(drawerInk(0.95f));
        cardDim.setColor(tone(L_DIM, 0xADFFFFFF));
        cardDimRight.setColor(tone(L_FAINT, 0x99FFFFFF));
        bigTemp.setColor(drawerInk(1));
        hourPaint.setColor(tone(L_DIM, 0xADFFFFFF));
        hourTemp.setColor(drawerInk(0.95f));
        pricePaint.setColor(drawerInk(0.95f));
        float titleBase = ROW_TITLE * k;
        if (drawerInputs > 0) c.drawText(Ui.tr("Inputs"), drawerRects[0].left + 4 * k, titleBase, rowTitle);
        if (drawerInputs < drawerItems.size()) c.drawText(Ui.tr("Shortcuts"), drawerRects[drawerInputs].left + 4 * k, titleBase, rowTitle);
        int top = -1;
        for (int i = 0; i < drawerItems.size(); i++) if (drawerFocus[i].value > 0.02f && (top < 0 || drawerFocus[i].value > drawerFocus[top].value)) top = i;
        for (int i = 0; i < drawerItems.size(); i++) if (i != top) drawDrawerTile(c, i);
        if (top >= 0) drawDrawerTile(c, top);
        if (Prefs.weather()) drawWeatherCard(c, weatherCard);
        if (Prefs.quotes() && HomeData.active().length > 0) drawQuotesCard(c, quotesCard);
    }

    /** Square drawer button: symbol and name; an input with no device is dimmed. Focused: white, with dark text. */
    private void drawDrawerTile(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.07f * f;
        RectF base = drawerRects[i];
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = 20 * k * s;
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 12 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.45f * f));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (f < 0.99f) drawerGlass(c, cell, r, 0.7f, 0x14000000, 6 * k);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(WHITE, f));
            c.drawRoundRect(cell, r, r, fill);
        }
        boolean off = it.kind == HomeApps.INPUT && !it.connected;
        int ink = blend(tone(off ? 0xFF8E8E93 : L_INK, off ? 0x6BFFFFFF : 0xF2FFFFFF), INK, f);
        Symbols.draw(getContext(), c, it.symbol, cx, cell.top + cell.height() * 0.4f, 34 * k * s, ink);
        drawerLabel.setColor(ink);
        drawerLabel.setTextSize(23 * k * s);
        float room = cell.width() - 16 * k, wide = drawerLabel.measureText(it.label);
        // A long name ("Home Settings", an input you renamed) shrinks a little before it gets cut.
        if (wide > room) drawerLabel.setTextSize(Math.max(18 * k * s, 23 * k * s * room / wide));
        fitPaint.set(drawerLabel);
        String label = TextUtils.ellipsize(it.label, fitPaint, room, TextUtils.TruncateAt.END).toString();
        c.drawText(label, cx, cell.top + cell.height() * 0.8f, drawerLabel);
        drawerLabel.setTextSize(23 * k);
        drawFocusRing(c, cell, r, f);
    }

    /** Weather for your location: now (large), the next 6 hours, and sunrise and sunset. */
    private void drawWeatherCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) weatherSnapshot.draw(c, r, drawerCardsKey(), cc -> drawWeather(cc, r));
        else drawWeather(c, r);
    }

    private void drawWeather(Canvas c, RectF r) {
        refreshDrawerText();
        float x = r.left + 34 * k;
        c.drawText(weatherTitle, x, r.top + 54 * k, cardTitle);
        HomeData.Weather wx = textWeather;
        if (Double.isNaN(wx.temp)) {
            c.drawText(Ui.tr("No weather data right now."), x, r.top + 120 * k, cardDim);
            return;
        }
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, 52 * k);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, r.top + 132 * k, 52 * k, drawerInk(0.95f));
        c.drawText(nowTemp, x + sw + 18 * k - Ui.leftBearing(bigTemp, nowTemp), r.top + 170 * k, bigTemp);
        c.drawText(HomeData.condition(wx.code, wx.isDay), x, r.top + 220 * k, cardText);
        c.drawText(highLow, x, r.top + 258 * k, cardDim);
        float split = r.left + 392 * k;
        fill.setShader(null);
        fill.setColor(tone(0x1A000000, 0x24FFFFFF));
        c.drawRoundRect(split - k, r.top + 34 * k, split + k, r.bottom - 34 * k, k, k, fill);
        float hx = split + 30 * k, hw = (r.right - 30 * k - hx) / 6;
        for (int i = 0; i < wx.hours.size() && i < 6; i++) {
            float hc = hx + hw * (i + 0.5f);
            c.drawText(hourText[i], hc, r.top + 96 * k, hourPaint);
            Symbols.draw(getContext(), c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), hc, r.top + 142 * k, 30 * k, drawerInk(0.9f));
            c.drawText(hourTempText[i], hc, r.top + 196 * k, hourTemp);
        }
        float sx = hx, base = r.top + 258 * k;
        if (wx.sunrise != 0) sx = sunItem(c, "sunrise", sunriseText, sx, base) + 36 * k;
        if (wx.sunset != 0) sunItem(c, "sunset", sunsetText, sx, base);
    }

    private void refreshDrawerText() {
        HomeData.Weather wx = data.weather;
        boolean settings = Prefs.version != textPrefs || !Ui.language.equals(textLanguage);
        textPrefs = Prefs.version;
        textLanguage = Ui.language;
        if (wx != textWeather || settings) {
            textWeather = wx;
            weatherTitle = Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city();
            nowTemp = HomeData.degrees(wx.temp);
            highLow = HomeData.highLow(wx, "   ");
            for (int i = 0; i < wx.hours.size() && i < 6; i++) {
                hourText[i] = HomeData.hour(wx.hours.get(i));
                hourTempText[i] = HomeData.degrees(wx.hourTemp.get(i));
            }
            sunriseText = Ui.trf("Sunrise {time}", "time", Ui.time(wx.sunrise));
            sunsetText = Ui.trf("Sunset {time}", "time", Ui.time(wx.sunset));
        }
        long minute = System.currentTimeMillis() / 60_000;
        if (data.pricesAt != textPricesAt || minute != textMinute || settings) {
            textPricesAt = data.pricesAt;
            textMinute = minute;
            String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
            staleText = stale.isEmpty() ? Ui.tr("24h change") : Ui.trf("quotes|{ago}", "ago", stale);
            for (int i = 0; i < HomeData.COINS.length; i++) {
                priceText[i] = HomeData.brl(data.prices[i]);
                changeText[i] = HomeData.change(data.changes[i]);
            }
        }
    }

    private float sunItem(Canvas c, String symbol, String label, float x, float base) {
        float size = 22 * k, sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(cardDim), size, tone(L_DIM, 0xB3FFFFFF));
        c.drawText(label, x + sw + 10 * k, base, cardDim);
        return x + sw + 10 * k + cardDim.measureText(label);
    }

    /** Prices in the configured currency with the 24 h change (green/red, like Apple's Stocks app). */
    private void drawQuotesCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) quotesSnapshot.draw(c, r, drawerCardsKey(), cc -> drawPrices(cc, r));
        else drawPrices(c, r);
    }

    /**
     * What the drawer cards show depends on: the data, the settings, the language, day or night, and the minute (the
     * sunrise and sunset, "12 min ago").
     */
    private String drawerCardsKey() {
        return data.weatherAt + "|" + data.pricesAt + "|" + Prefs.version + "|" + Ui.language + "|"
                + Float.floatToIntBits(dark()) + "|" + System.currentTimeMillis() / 60_000;
    }

    /**
     * Android 8 to 11: a drawer card's content (its text and symbols, not its glass) kept as a bitmap. The drawer is
     * redrawn on every frame of its focus animation, and laying that text out again took ~15 ms a frame on a Mi Box 3.
     */
    private static final class Snapshot {
        private Bitmap bitmap;
        private String key = "";

        void draw(Canvas c, RectF r, String key, Consumer<Canvas> content) {
            int left = Math.round(r.left), top = Math.round(r.top);
            int w = Math.max(1, Math.round(r.right) - left), h = Math.max(1, Math.round(r.bottom) - top);
            if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                this.key = "";
            }
            if (!key.equals(this.key)) {
                bitmap.eraseColor(0);
                Canvas cc = new Canvas(bitmap);
                cc.translate(-left, -top);
                content.accept(cc);
                this.key = key;
            }
            c.drawBitmap(bitmap, left, top, null);
        }
    }

    private final Snapshot weatherSnapshot = new Snapshot(), quotesSnapshot = new Snapshot();

    private void drawPrices(Canvas c, RectF r) {
        float x = r.left + 34 * k, right = r.right - 34 * k;
        refreshDrawerText();
        c.drawText(Ui.tr("Crypto"), x, r.top + 54 * k, cardTitle);
        c.drawText(staleText, right, r.top + 54 * k, cardDimRight);
        float cap = Ui.cap(cardText);
        int[] coins = HomeData.active();
        for (int j = 0; j < coins.length; j++) {
            int i = coins[j];
            float base = r.top + (110 + 52 * j) * k, mid = base - cap / 2;
            float gw = Glyphs.width(getContext(), HomeData.COINS[i], cap);
            Glyphs.coin(getContext(), c, HomeData.COINS[i], x + 14 * k, mid, cap, tone(L_DIM, 0xCCFFFFFF));
            c.drawText(HomeData.coinName(i), x + Math.max(gw, 28 * k) / 2 + 30 * k, base, cardText);
            c.drawText(priceText[i], right - 128 * k, base, pricePaint);
            double change = data.changes[i];
            if (Double.isNaN(change)) continue;
            int color = change >= 0 ? tone(0xFF248A3D, UP_COLOR) : tone(0xFFD70015, DOWN_COLOR);
            tmp.set(right - 108 * k, mid - 17 * k, right, mid + 17 * k);
            fill.setShader(null);
            fill.setColor(Ui.alpha(color, 0.2f));
            c.drawRoundRect(tmp, 17 * k, 17 * k, fill);
            changePaint.setColor(color);
            c.drawText(changeText[i], tmp.centerX(), mid + Ui.halfCap(changePaint), changePaint);
        }
    }

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        revealShelf(snap);
        // Only the group whose focus changed is redrawn (moving along the shelf doesn't redraw the hero or capsule).
        boolean tilesChanged = aim(tileFocus, !covered() && zone == Z_SHELF ? shelfIndex : -1, snap);
        boolean statusChanged = aim(statusFocus, !covered() && zone == Z_STATUS ? statusIndex : -1, snap);
        boolean drawerChanged = aim(drawerFocus, !covered() && zone == Z_DRAWER ? drawerIndex : -1, snap);
        if (zone == Z_WATCH) revealWatch(snap);
        boolean watchChanged = aim(watchFocus, !covered() && zone == Z_WATCH ? watchIndex : -1, snap);
        boolean heroChanged = heroFocus.target != (!covered() && zone == Z_HERO ? 1 : 0);
        heroFocus.target = !covered() && zone == Z_HERO ? 1 : 0;
        if (snap) heroFocus.snap(heroFocus.target);
        boolean topChanged = aim(topFocus, !covered() && zone == Z_TOP ? topIndex : -1, snap);
        heroChanged |= aim(cardFocus, !covered() && zone == Z_CARDS ? slotOf(cardIndex) : -1, snap) | topChanged;
        if (!covered() && zone == Z_CARDS && cardIndex < cards.size()) {
            featured = slotOf(cardIndex);
            if (featuredCard != cardIndex) {
                featuredCard = cardIndex;
                showFeaturedBackdrop();
            }
        }
        if (aim(cardWide, featured, snap)) heroChanged = true;
        if (snap) layoutCards();
        statusChanged |= topChanged;
        tilesDirty |= snap || tilesChanged;
        statusDirty |= snap || statusChanged;
        heroDirty |= snap || heroChanged;
        drawerDirty |= snap || drawerChanged;
        watchDirty |= snap || watchChanged;
        spoken.focusMoved();
    }

    /** All Apps or the Search page is over the home screen: nothing under them holds the focus. */
    private boolean covered() {
        return sheetOpen || searchOpen;
    }

    /** Aims a group's springs (only `focused` at 1); returns true if any target changed. */
    private static boolean aim(Spring[] springs, int focused, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < springs.length; i++) {
            float target = i == focused ? 1 : 0;
            changed |= springs[i].target != target;
            springs[i].target = target;
            if (snap) springs[i].snap(target);
        }
        return changed;
    }

    // MARK: - "All Apps" sheet

    private void setOthers(List<HomeApps.Item> list) {
        others = list;
        gridFocus = new Spring[list.size()];
        for (int i = 0; i < gridFocus.length; i++) gridFocus[i] = new Spring(0.3f, 0.82f, 0);
        gridLabels = null;
        gridIndex = Math.min(gridIndex, Math.max(0, list.size() - 1));
        retargetGrid(true);
        gridDirty = true;
    }

    private void openSheet() {
        sheetIsSearch = false;   // the layer last drew the search page: back to the grid
        sheetOpen = true;
        gridIndex = 0;
        scroll.snap(0);
        retargetGrid(true);
        retarget(false);
        sheet.target = 1;
        gridDirty = true;
    }

    private void closeSheet() {
        sheetOpen = false;
        sheet.target = 0;
        retarget(false);
        if (shelfEdited) {     // the grid was kept as it was while open: apps that went to the shelf leave it now
            shelfEdited = false;
            reload();
        }
    }

    private void retargetGrid(boolean snap) {
        for (int i = 0; i < gridFocus.length; i++) {
            gridFocus[i].target = sheetOpen && i == gridIndex ? 1 : 0;
            if (snap) gridFocus[i].snap(gridFocus[i].target);
        }
        int rows = Math.max(1, (others.size() + COLUMNS - 1) / COLUMNS);
        int visible = Math.min(rows, VISIBLE_ROWS);
        int r = gridIndex / COLUMNS;
        float target = scroll.target;
        if (r < target) target = r;
        else if (r > target + visible - 1) target = r - visible + 1;
        scroll.target = Math.max(0, Math.min(rows - visible, target));
        if (snap) scroll.snap(scroll.target);
        spoken.focusMoved();
    }

    private void drawSheet(Canvas c) {
        c.drawColor(0xA8000000);
        float shift = scroll.value * GRID_ROW * k;
        c.drawText(Ui.tr("All Apps"), w / 2f, 124 * k - shift, sheetTitle);
        int n = others.size();
        if (gridLabels == null || gridLabels.length != n) {
            gridLabels = new String[n];
            for (int i = 0; i < n; i++) {
                gridLabels[i] = TextUtils.ellipsize(others.get(i).label, gridLabel, (GRID_W - 10) * k, TextUtils.TruncateAt.END).toString();
            }
        }
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (1920 - gridWidth) / 2 * k;
        float gridTop = GRID_TOP * k;
        for (int i = 0; i < n; i++) {
            int r = i / COLUMNS, col = i % COLUMNS;
            float y = gridTop + r * GRID_ROW * k - shift;
            if (y > h || y + GRID_ROW * k < 0) continue;
            float x = left + col * (GRID_W + GRID_GAP) * k;
            gridCell.set(x, y, x + GRID_W * k, y + GRID_H * k);
            float f = gridFocus[i].value;
            drawCard(c, others.get(i), gridCell, GRID_RADIUS * k, f, 0.08f);
            if (sheetOpen && i == gridIndex) focusedCell.set(cell);
            gridLabel.setColor(blend(0x99FFFFFF, 0xFFFFFFFF, Ui.clamp(f)));
            c.drawText(gridLabels[i], gridCell.centerX(), gridCell.bottom + (40 + 6 * f) * k, gridLabel);
        }
    }

    /** 16:9 sheet card. Focused (the shelf's subtle focus): it grows, with a shadow, a soft sheen and a thin border. */
    private void drawCard(Canvas c, HomeApps.Item it, RectF base, float radius, float f, float grow) {
        drawCard(c, it.tile, it.tileShader, base, radius, f, grow);
    }

    /** The same card from a picture (null: a plain dark card). Leaves the grown rectangle in `cell`. */
    private void drawCard(Canvas c, Bitmap tile, BitmapShader tileShader, RectF base, float radius, float f, float grow) {
        float s = 1 + grow * f;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = radius * s;
        float fc = Ui.clamp(f);
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 16 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.55f * fc));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (tile != null) {
            roundImage(c, tile, tileShader, cell, r);
        } else {
            fill.setShader(null);
            fill.setColor(0xFF2C2C2E);
            c.drawRoundRect(cell, r, r, fill);
        }
        if (f > 0.01f) {
            float bh = base.height();
            if (cardSheen == null || cardSheenH != bh) {
                cardSheen = new LinearGradient(0, -bh / 2, 0, 0, 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                cardSheenH = bh;
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-base.width() / 2, -bh / 2, base.width() / 2, bh / 2);
            sheen.setShader(cardSheen);
            sheen.setAlpha(Math.round(255 * fc));
            c.drawRoundRect(tmp, radius, radius, sheen);
            sheen.setAlpha(255);
            c.restore();
        }
        if (fc < 0.99f) {
            veil.setColor(Ui.alpha(0xFF000000, 0.08f * (1 - fc)));
            c.drawRoundRect(cell, r, r, veil);
        }
        border.setColor(0x14FFFFFF);
        c.drawRoundRect(cell, r, r, border);
        drawFocusRing(c, cell, r, fc);
    }

    // MARK: - Toasts

    void showToast(String text) {
        toasts.add(text);
        announceForAccessibility(text);
        wake();
    }

    /** Advances the toast queue; returns true while a toast fades in or out (animation). */
    private boolean tickToast(long up) {
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null;
            toastLayer.invalidate();
        }
        if (toast == null && toastPaint != null) {
            String next = toasts.poll();
            if (next != null) {
                toast = layout(next);
                toastAt = up;
            }
        }
        if (toast == null) return false;
        long age = up - toastAt;
        boolean fading = age < 350 || age > TOAST_MS - 450;
        if (fading) toastLayer.invalidate();
        return fading;
    }

    private void drawToast(Canvas c) {
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 28 * k, padY = 18 * k, icon = 26 * k, iconW = Symbols.width(getContext(), "bell", icon);
        float tw = toast.getWidth() + iconW + 16 * k, th = toast.getHeight();
        float toastTop = 64 * k - (1 - a) * 16 * k;
        tmp.set((w - tw) / 2f - padX, toastTop, (w + tw) / 2f + padX, toastTop + th + 2 * padY);
        int save = c.saveLayerAlpha(tmp.left - 4, tmp.top - 4, tmp.right + 4, tmp.bottom + 4, Math.round(255 * a));
        scene.glass(c, tmp, Math.min(tmp.height() / 2, 32 * k), SMOKE, 0.55f, 0, 0, 0.02f, 1);
        Symbols.draw(getContext(), c, "bell", tmp.left + padX + iconW / 2, tmp.centerY(), icon, 0xEBFFFFFF);
        c.translate(tmp.left + padX + iconW + 16 * k, tmp.top + padY);
        toast.draw(c);
        c.restoreToCount(save);
    }

    private StaticLayout layout(String text) {
        int max = Math.round(720 * k);
        int width = (int) Math.min(max, Math.ceil(Layout.getDesiredWidth(text, toastPaint)));
        return StaticLayout.Builder.obtain(text, 0, text.length(), toastPaint, Math.max(1, width))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    /** New notices from the main process (mode switches, the check after the TV boots): those from the last 24 h. */
    private void checkNotices() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            JSONArray list = HomeMode.state(c).optJSONArray("notices");
            if (list == null) return;
            long seen = ui.getLong("seenNotice", 0), newest = seen;
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            final List<String> fresh = new ArrayList<>();
            for (int i = list.length() - 1; i >= 0; i--) {   // the list comes newest first
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at");
                if (at <= seen || at < cutoff) continue;
                fresh.add(o.optString("text"));
                newest = Math.max(newest, at);
            }
            if (newest > seen) ui.edit().putLong("seenNotice", newest).apply();
            if (!fresh.isEmpty()) handler.post(() -> { for (String t : fresh) showToast(t); });
        });
    }

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    private void reload() {
        final Context c = getContext().getApplicationContext();
        // The shelf and All Apps (100 to 500 ms of package queries on a TV) only when they can have changed.
        long up = SystemClock.uptimeMillis();
        refreshTabs();
        final boolean apps = appsStale || Prefs.version != appsVersion || Ui.generation != appsGeneration
                || up - appsAt > APPS_MAX_AGE_MS;
        if (apps) {
            appsStale = false;
            appsVersion = Prefs.version;
            appsGeneration = Ui.generation;
            appsAt = up;
        }
        LOADER.execute(() -> {
            final List<HomeApps.Item> s = apps ? HomeApps.shelf(c, true) : null;
            final List<HomeApps.Item> st = statusList(c);
            final List<HomeApps.Item> d = drawerList(c);
            final List<HomeApps.Item> o = apps ? HomeApps.others(c) : null;
            final List<HomeApps.Item> top = apps ? HomeApps.topButtons() : null;   // they change with Prefs too
            handler.post(() -> {
                if (top != null) setLightButtons(top);
                if (s != null) setShelf(s);
                setStatusItems(st);
                setDrawerItems(d);
                if (o != null && !sheetOpen) setOthers(o);
                else if (o != null) appsStale = true;   // All Apps is open: its list comes next time
            });
        });
    }

    /** Apps installed, removed or updated (also while another app is open): the next reload reads them again. */
    private final LauncherApps.Callback packages = new LauncherApps.Callback() {
        @Override public void onPackageAdded(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackageRemoved(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackageChanged(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackagesAvailable(String[] pkgs, UserHandle user, boolean replacing) { appsChanged(); }
        @Override public void onPackagesUnavailable(String[] pkgs, UserHandle user, boolean replacing) { appsChanged(); }
    };

    private void appsChanged() {
        appsStale = true;
        if (running) reloadSoon();
    }

    // MARK: - Keys: only the arrows, OK and Back (system keys pass through to the system)

    boolean onKey(KeyEvent e) {
        wake();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (reordering) return reorderKey(e, down);
        if (menuOpen) return menuKey(e, down);
        if (infoOpen) return infoKey(e, down);
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_SEARCH || code == KeyEvent.KEYCODE_ASSIST || code == KeyEvent.KEYCODE_VOICE_ASSIST) {
            // The remote's Assistant button: Google's own results live in the stock home screen, which is off in Free
            // My TV mode, so the button searches here, by voice.
            if (!picksOn()) return false;
            if (down && e.getRepeatCount() == 0) {
                if (searchOpen) startVoice(); else openSearch(true);
            }
            return true;
        }
        if (searchOpen) return searchKey(e, down);
        if (down && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER
                && code != KeyEvent.KEYCODE_NUMPAD_ENTER) {
            holdArmed = false;      // another key while OK is held: no move mode
            handler.removeCallbacks(holdStart);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Opens on release, and only if the press happened here: the release of an OK pressed inside another
                // app opens nothing.
                // Held (~0.5 s) on a shelf app: move mode; on an app in All Apps: on or off the shelf. A timer, since
                // not every remote repeats a held key, or the long-press repeat, whichever comes first; the release
                // after a hold opens nothing.
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        okHeld = false;
                        handler.removeCallbacks(holdStart);
                        holdArmed = sheetOpen ? canToggleShelf(gridIndex) : canReorder() || canRateCard() || canWindowMenu() || canRemoveWatch();
                        if (holdArmed) handler.postDelayed(holdStart, HOLD_MS);
                    } else if (okDown && holdArmed && e.isLongPress()) {
                        hold();
                    }
                } else {
                    holdArmed = false;
                    handler.removeCallbacks(holdStart);
                    if (okDown && !okHeld && !e.isCanceled() && spoken.keyClick()) activate();
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) back();
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private void back() {
        if (sheetOpen) {
            closeSheet();
        } else if (zone == Z_DRAWER || zone == WIDGETS) {
            closeDrawer();
            retarget(false);
        } else if (zone == Z_WATCH) {
            closeWatch();
            retarget(false);
        } else if (zone != Z_SHELF) {
            zone = Z_SHELF;
            retarget(false);
        } else if (picksOn()) {   // Back on the shelf: up to the tabs (the user's ask: Back twice from a card lands on the tabs)
            zone = Z_TOP;
            topIndex = tab;
            retarget(false);
        } else {
            return;
        }
        click.move();
    }

    private void move(int dx, int dy) {
        if (sheetOpen) {
            moveGrid(dx, dy);
            return;
        }
        if (moveLight(dx, dy)) return;
        switch (zone) {
            case Z_SHELF:
                if (dx != 0) {
                    int next = wrap(shelfIndex, dx, shelf.size());
                    if (next == shelfIndex) return;
                    shelfIndex = next;
                } else if (dy < 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else if (!statusItems.isEmpty()) {
                        zone = Z_STATUS;
                        statusIndex = shelf.isEmpty() ? 0 : nearest(statusRects, tileRects[shelfIndex].centerX());
                    } else {
                        return;
                    }
                } else {
                    if (!watchItems.isEmpty()) openWatch();
                    else if (!drawerItems.isEmpty()) openDrawer();
                    else return;
                }
                break;
            case Z_WATCH:
                if (dx != 0) {
                    int next = wrap(watchIndex, dx, watchItems.size());
                    if (next == watchIndex) return;
                    watchIndex = next;
                } else if (dy < 0) {
                    closeWatch();
                } else {
                    if (drawerItems.isEmpty()) return;
                    openDrawer();
                }
                break;
            case Z_HERO:
                if (dx != 0) {
                    if (!browse(dx)) return;
                } else if (dy < 0) {
                    if (statusItems.isEmpty()) return;
                    zone = Z_STATUS;
                    statusIndex = 0;
                } else {
                    zone = Z_SHELF;
                }
                break;
            case Z_STATUS:
                if (dx != 0) {
                    int next = wrap(statusIndex, dx, statusItems.size());
                    if (next == statusIndex) return;
                    statusIndex = next;
                } else if (dy > 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else {
                        zone = Z_SHELF;
                        if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, statusRects[statusIndex].centerX());
                    }
                } else {
                    return;
                }
                break;
            case WIDGETS: {
                List<Integer> on = widgets();
                int at = on.indexOf(widgetIndex);
                if (dy < 0 || at < 0) {
                    zone = Z_DRAWER;
                } else if (dx != 0 && on.size() > 1) {
                    widgetIndex = on.get(wrap(at, dx, on.size()));
                } else {
                    return;
                }
                break;
            }
            default:
                if (dx != 0) {
                    int next = wrap(drawerIndex, dx, drawerItems.size());
                    if (next == drawerIndex) return;
                    drawerIndex = next;
                } else if (dy < 0) {
                    closeDrawer();
                    if (!watchItems.isEmpty()) openWatch();   // up from the drawer: the row again, then the shelf
                } else if (talkBack() && !widgets().isEmpty()) {
                    // Only with TalkBack: the widgets are there to be read, and this is how it gets to them.
                    zone = WIDGETS;
                    widgetIndex = widgets().get(0);
                } else {
                    return;
                }
                break;
        }
        retarget(false);
        click.move();
    }

    private static int nearest(RectF[] rects, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private void moveGrid(int dx, int dy) {
        int n = others.size();
        if (n == 0) return;
        int r = gridIndex / COLUMNS, col = gridIndex % COLUMNS;
        int rows = (n + COLUMNS - 1) / COLUMNS;
        int next = gridIndex;
        if (dx != 0) {   // along the row, wrapping at its ends
            int first = r * COLUMNS, last = Math.min(n, first + COLUMNS) - 1;
            next = first + wrap(gridIndex - first, dx, last - first + 1);
        } else {   // down from the last row comes back to the first, up from the first to the last
            next = Math.min(n - 1, wrap(r, dy, rows) * COLUMNS + col);
        }
        if (next == gridIndex) return;
        gridIndex = next;
        retargetGrid(false);
        click.move();
        gridDirty = true;
    }

    private void activate() {
        wake();
        if (reordering) {   // TalkBack's click while moving an app: drop it
            dropReorder();
            return;
        }
        HomeApps.Item it;
        RectF r;
        if (!sheetOpen && zone == WIDGETS) return;   // a widget is only read
        if (searchOpen) {
            searchActivate();
            return;
        }
        if (sheetOpen) {
            if (others.isEmpty()) return;
            it = others.get(gridIndex);
            r = focusedCell;
        } else if (zone == Z_HERO) {   // the title's page: you pick where to watch it (Stremio included), even with one app
            Recs.Pick p = currentPick();
            if (p != null && p.detailed) openInfo(p);
            return;
        } else if (zone == Z_STATUS) {
            if (statusItems.isEmpty()) return;
            it = statusItems.get(statusIndex);
            r = statusRects[statusIndex];
        } else if (zone == Z_DRAWER) {
            if (drawerItems.isEmpty()) return;
            it = drawerItems.get(drawerIndex);
            r = drawerRects[drawerIndex];
        } else if (zone == Z_TOP) {
            if (topIndex == SEARCH_SLOT) {
                openSearch(true);   // listening at once; Back on the voice screen leaves the page open, to type
                return;
            }
            if (topIndex < TABS) {
                if (topIndex == tab) otherPicks(); else selectTab(topIndex);   // OK on the open tab: its next set
                return;
            }
            it = lightButtons.get(topIndex - TABS);
            r = topRects[topIndex];
        } else if (zone == Z_CARDS) {   // the title's page, as on the hero
            Recs.Pick p = cardIndex < cards.size() ? cards.get(cardIndex) : null;
            if (p != null && p.detailed) openInfo(p);
            return;
        } else if (zone == Z_WATCH) {
            if (watchIndex >= watchItems.size()) return;
            openWatchEntry(watchItems.get(watchIndex), watchRect(watchIndex));
            return;
        } else {
            if (shelf.isEmpty()) return;
            it = shelf.get(shelfIndex);
            r = tileRects[shelfIndex];
        }
        click.open();
        if (it.kind == HomeApps.ALL_APPS) {
            openSheet();
            return;
        }
        if (it.kind == HomeApps.INPUTS) {
            openDrawer();
            for (int i = 0; i < drawerItems.size(); i++) {
                if (drawerItems.get(i).kind == HomeApps.INPUT) {
                    drawerIndex = i;
                    break;
                }
            }
            retarget(false);
            return;
        }
        boolean opened = HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top),
                Math.round(r.width()), Math.round(r.height()));
        if (!opened) showToast(Ui.trf("Couldn't open {app}.", "app", it.label));
    }

    // MARK: - Holding OK: All Apps puts the app on the shelf or takes it off; on the shelf, the app is lifted to move

    private void hold() {
        if (searchOpen) {
            if (canRateResult()) rateCard(results.get(sIndex), resultRect(sIndex, resultCell), false);
        } else if (sheetOpen) toggleShelf();
        else if (canRateCard()) rateCard(cards.get(cardIndex), cardRects[slotOf(cardIndex)], true);
        else if (canRemoveWatch()) openWatchMenu();
        else if (canWindowMenu()) openWindowMenu();
        else startReorder();
    }

    /** Hold OK on the Trending tab: pick its window; on the Pan-India tab: its language. */
    private boolean canWindowMenu() {
        if (covered() || zone != Z_TOP || topIndex >= TABS || topIndex >= nTabs()) return false;
        Tabs.Tab t = tabList.get(topIndex);
        return t.trending || t.pan || t.kind == null || t.other;
    }

    /** What a tab's hold menu offers and what is chosen now. */
    private int pillsOf(Tabs.Tab t) {
        return t.trending ? WINDOWS : t.pan ? LANGUAGES : t.other ? OTHERS : t.kind == null ? KINDS : NO_PILLS;
    }

    private String chosenOf(Tabs.Tab t) {
        switch (pillsOf(t)) {
            case WINDOWS: return "mode:" + (Recs.rated(recs.trendingWindow()) ? "rated" : "trend") + "," + recs.trendingWindow() + ",kind:" + recs.tabKind(t);
            case LANGUAGES: return recs.panLanguage();
            case KINDS: return "kind:" + recs.tabKind(t);
            case OTHERS: return "lang:" + recs.otherLanguage();
            default: return "";
        }
    }

    private void openWindowMenu() {
        okHeld = true;
        menuActions.clear();
        Tabs.Tab t = tabList.get(topIndex);
        menuTab = t;
        menuPills = pillsOf(t);
        if (menuPills == WINDOWS) {
            windowPills();
        } else if (menuPills == LANGUAGES) {
            menuActions.add("");
            for (String l : t.languages) menuActions.add(l);
        } else if (menuPills == OTHERS) {   // Upcoming: its language
            menuActions.add("lang:");
            for (String l : t.languages) menuActions.add("lang:" + l);
        } else {
            menuActions.add("kind:");
            menuActions.add("kind:movie");
            menuActions.add("kind:tv");
        }
        openMenu(null, topRects[topIndex]);
        String[] chosen = chosenOf(t).split(",");   // several (the Trending tab: its list, its window, its kind): the focus starts on the window
        menuIndex = Math.max(0, menuActions.indexOf(chosen[menuPills == WINDOWS ? 1 : chosen.length - 1]));
        aim(menuFocus, menuIndex, true);
    }

    /** The Trending tab's pills: its two lists (trending, top rated), movies or series, then the windows of the list chosen. */
    private void windowPills() {
        menuActions.clear();
        menuActions.add("mode:trend");
        menuActions.add("mode:rated");
        menuActions.add("kind:");   // the second row: movies and series, movies, series
        menuActions.add("kind:movie");
        menuActions.add("kind:tv");
        for (String w : Recs.rated(recs.trendingWindow()) ? Recs.RATED : Recs.TREND) menuActions.add(w);   // the third: the list's windows
    }

    /** The other list of the Trending tab: its default window, its windows take the row, and the menu stays open to pick one. */
    private void switchList(boolean rated) {
        click.open();
        if (rated != Recs.rated(recs.trendingWindow())) recs.setTrendingWindow(rated ? Recs.RATED[Recs.RATED.length - 1] : Recs.TREND[0]);
        windowPills();
        layoutMenu();
        menuIndex = rated ? 1 : 0;   // stays on the list's pill: Down reaches the kinds, then its windows
        aim(menuFocus, menuIndex, false);
        layoutLight();
        retarget(true);
        updateCards();
        heroDirty = true;
        toastLayer.invalidate();
        announceForAccessibility(menuLabel(menuActions.get(menuIndex)));
    }

    private static final String OTHER_PICKS = "other picks", TRAILER = "trailer", REMIND = "remind";   // the menu's entries that are not verdicts (REMIND: 👍 for an upcoming title)
    private static final String STREAM = "stream", STREMIO = "com.stremio.one";   // Stremio (with its Torrentio add-on) finds a stream for any title
    private static final String YOUTUBE_TV = "com.google.android.youtube.tv";

    /** The trailer in the YouTube app (its own page for the video; the app's home if it won't take the address). */
    private void playTrailer(Recs.Pick p, RectF from) {
        HomeApps.Item yt = HomeApps.app(YOUTUBE_TV, "YouTube", "https://www.youtube.com/watch?v=" + p.trailer);
        boolean opened = HomeApps.open(activity, yt, this, Math.round(from.left), Math.round(from.top),
                Math.round(from.width()), Math.round(from.height()));
        if (!opened) showToast(Ui.trf("Couldn't open {app}.", "app", "YouTube"));
    }

    /** Stremio is on the TV and the title is out (an upcoming one streams nowhere yet). */
    private boolean streamable(Recs.Pick p) {
        return !p.upcoming() && HomeApps.launchIntent(activity.getPackageManager(), STREMIO) != null;
    }

    /** Stremio on the title's page (its IMDb id; Torrentio lists the streams there), or its search when TMDB has no id. */
    private void openStream(Recs.Pick p, RectF from) {
        String uri = p.imdbId != null ? "stremio:///detail/" + (p.kind.equals("tv") ? "series" : "movie") + "/" + p.imdbId
                : "stremio:///search?search=" + android.net.Uri.encode(p.title);
        Watching.launched(getContext(), p, STREMIO, uri);
        HomeApps.Item st = HomeApps.app(STREMIO, "Stremio", uri);
        boolean opened = HomeApps.open(activity, st, this, Math.round(from.left), Math.round(from.top),
                Math.round(from.width()), Math.round(from.height()));
        if (!opened) showToast(Ui.trf("Couldn't open {app}.", "app", "Stremio"));
    }

    /** Hold OK on a pick: say what you think of it (Feedback), with the TV's own dialog. */
    private boolean canRateCard() {
        return !sheetOpen && zone == Z_CARDS && cardIndex < cards.size() && cards.get(cardIndex).detailed;
    }

    /**
     * Hold OK on a pick: its menu, a row of round buttons under the card (as a long press on Apple TV): the trailer,
     * Watched (how was it), your watchlist, and on a home card (`home`) Remove (it leaves the picks for good) and Other picks.
     * A search result gets no Remove: it isn't in a row to be removed from.
     */
    private void rateCard(Recs.Pick p, RectF base, boolean home) {
        okHeld = true;   // the release opens nothing
        menuVerdict = Account.verdict(getContext(), Recs.key(p));
        menuActions.clear();
        if (p.trailer != null) menuActions.add(TRAILER);   // the page (OK) has the rest: where it streams, Stremio
        if (p.upcoming()) {   // not out yet: nothing to judge, only to remember (your TMDB watchlist, shown first once it's on your apps)
            menuActions.add(REMIND);
            if (Feedback.UP.equals(menuVerdict)) menuVerdict = REMIND;
        } else {
            menuActions.add(Feedback.WATCHED);
            menuActions.add(Feedback.UP);
            if (home) menuActions.add(Feedback.DOWN);
        }
        if (home) menuActions.add(OTHER_PICKS);
        openMenu(p, base);
    }

    /** "Watched": how was it, as text pills under the card; the rating goes to the profile and to your TMDB account. */
    private void openRatingMenu(Recs.Pick p, RectF base) {
        menuActions.clear();
        for (String a : RATING_ACTIONS) menuActions.add(a);
        openMenu(p, base);
        menuPills = RATINGS;
        menuTab = null;
        layoutMenu();
        int given = Account.watchedRating(getContext(), Recs.key(p));
        menuIndex = given > 0 ? Math.max(0, menuActions.indexOf("rate:" + given)) : 2;   // "Good" to start from
        aim(menuFocus, menuIndex, true);
        announceForAccessibility(Ui.tr("Watched") + ". " + menuLabel(menuActions.get(menuIndex)));
    }

    /** The chosen pill of the open menu: the tab's window, language or kind, or the title's rating. */
    private String chosen() {
        if (menuPills == RATINGS) return menuPick == null ? "" : "rate:" + Account.watchedRating(getContext(), Recs.key(menuPick));
        return menuTab == null ? "" : chosenOf(menuTab);
    }

    private void rate(Recs.Pick p, int value) {
        Account.rate(getContext(), Recs.key(p), p.title, value);
        Feedback.add(getContext(), recs.item(p), Feedback.WATCHED, value);
        p.verdict = Feedback.WATCHED;
        recs.rerank();
        recs.syncSoon();
        heroDirty = searchDirty = true;
        updateCards();   // watched: out of the rows
        showToast(Ui.trf("Watched · {n}/10", "n", value) + accountNote());
        if (infoOpen) for (int i = 0; i < infoActions.size(); i++) infoLabels[i] = infoLabel(i);
    }

    private void openMenu(Recs.Pick p, RectF base) {
        menuPick = p;
        if (p != null) {   // a title's menu: round buttons (the pills and their panel are a tab's own)
            menuPills = NO_PILLS;
            menuTab = null;
        }
        menuIndex = 0;
        // The card as it is drawn focused (grown 5 %, risen 6 px): it stays bright, the row sits under it.
        float grow = 1.05f, cx = base.centerX(), cy = base.centerY() - 6 * k;
        menuCard.set(cx - base.width() * grow / 2, cy - base.height() * grow / 2, cx + base.width() * grow / 2, cy + base.height() * grow / 2);
        layoutMenu();
        menuOpen = true;
        menuOkDown = false;
        menu.target = 1;
        for (Spring sp : menuFocus) sp.snap(0);
        aim(menuFocus, 0, false);
        heroDirty = true;
        click.open();
        if (p != null) announceForAccessibility(p.title + ". " + menuLabel(menuActions.get(0)));
        else announceForAccessibility(menuLabel(menuActions.get(Math.max(0, menuActions.indexOf(chosen())))));
        wake();
        toastLayer.invalidate();
    }

    private static final float MENU_TILE_W = 152, MENU_TILE_H = 86;   // the Info page's "Watch on" tiles: 16:9

    /** The buttons centered under the card, kept inside the content width. */
    private void layoutMenu() {
        float w1 = L_BUTTON * k, h1 = L_BUTTON * k, gap = L_BUTTON_GAP * k;
        int n = menuActions.size();
        float width = 0;
        boolean pills = menuPills != NO_PILLS;
        String chosen = pills ? chosen() : "";
        if (menuTab != null) {   // a tab's choices: a panel under the tab bar, one row per group (the Trending tab: its lists, then their windows)
            float pad = MENU_PAD * k, rowGap = 14 * k;
            int rows = 1;
            for (int i = 0; i < n; i++) rows = Math.max(rows, menuRow(i) + 1);
            float[] rowW = new float[rows];
            int[] rowN = new int[rows];
            for (int i = 0; i < n; i++) {
                int r = menuRow(i);
                rowW[r] += lTip.measureText(pillLabel(menuActions.get(i), chosen)) + 2 * 28 * k + (rowN[r]++ > 0 ? gap : 0);
            }
            for (float rw : rowW) width = Math.max(width, rw);
            width += 2 * pad;
            float height = rows * h1 + (rows - 1) * rowGap + 2 * pad;
            float left = Math.max(MARGIN * k, Math.min(CONTENT_R * k - width, menuCard.left - pad)), top = menuCard.bottom + 12 * k;
            menuPanel.set(left, top, left + width, top + height);
            float[] x = new float[rows];
            for (int r = 0; r < rows; r++) x[r] = left + pad;
            for (int i = 0; i < n; i++) {
                int r = menuRow(i);
                float wi = lTip.measureText(pillLabel(menuActions.get(i), chosen)) + 2 * 28 * k, cy = top + pad + h1 / 2 + r * (h1 + rowGap);
                menuRects[i].set(x[r], cy - h1 / 2, x[r] + wi, cy + h1 / 2);
                x[r] += wi + gap;
            }
            return;
        }
        for (int i = 0; i < n; i++) width += (pills ? lTip.measureText(pillLabel(menuActions.get(i), chosen)) + 2 * 28 * k : w1) + (i > 0 ? pillGap(i, gap) : 0);
        // A card's row sits under the card: the focused entry's name, then the row.
        float left = Math.max(MARGIN * k, Math.min(CONTENT_R * k - width, menuCard.centerX() - width / 2));
        float cy = menuCard.bottom + (pills ? 56 : 104) * k;
        if (menuPills == WATCH_MENU) cy = menuCard.top - 56 * k;   // the card is at the foot of the screen: its pill sits above it
        float x = left;
        for (int i = 0; i < n; i++) {
            float wi = pills ? lTip.measureText(pillLabel(menuActions.get(i), chosen)) + 2 * 28 * k : w1;
            menuRects[i].set(x, cy - h1 / 2, x + wi, cy + h1 / 2);
            x += wi + (i + 1 < n ? pillGap(i + 1, gap) : gap);
        }
    }

    private static final float MENU_PAD = 20;   // the panel's padding around its rows

    /** The row of a tab menu's entry: the Trending tab's lists above, movies or series under them, their windows below; every other menu is one row. */
    private int menuRow(int i) {
        if (menuPills != WINDOWS) return 0;
        String a = menuActions.get(i);
        return a.startsWith("mode:") ? 0 : a.startsWith("kind:") ? 1 : 2;
    }

    /** The next entry along the row of `at`, wrapping: past the row's last entry comes its first. */
    private int alongRow(int at, int dx) {
        int row = menuRow(at), n = menuActions.size();
        for (int step = 1; step < n; step++) {
            int i = wrap(at, dx * step, n);
            if (menuRow(i) == row) return i;
        }
        return at;
    }

    /** The entry of `row` nearest (horizontally) to the focused one, or -1 when the menu has no such row. */
    private int nearestInRow(int row) {
        int best = -1;
        float cx = menuRects[menuIndex].centerX();
        for (int i = 0; i < menuActions.size(); i++) {
            if (menuRow(i) != row) continue;
            if (best < 0 || Math.abs(menuRects[i].centerX() - cx) < Math.abs(menuRects[best].centerX() - cx)) best = i;
        }
        return best;
    }

    private void moveMenuFocus(int next) {
        menuIndex = next;
        aim(menuFocus, menuIndex, false);
        click.move();
        announceForAccessibility(menuLabel(menuActions.get(menuIndex)));
    }

    /** A pill's text: the chosen one carries a check mark (tvOS's pickers), so the focused pill stays white with dark text. */
    private String pillLabel(String action, String chosen) {
        boolean on = ("," + chosen + ",").contains("," + action + ",");   // chosen: one entry, or several comma separated (Others)
        return on ? "\u2713 " + menuLabel(action) : menuLabel(action);
    }

    /** A wider gap before the second group of pills (the Others tab's languages after its sides). */
    /** A wider gap between two groups of pills in one row (the Trending tab's lists, then its windows). */
    private float pillGap(int i, float gap) {
        return i > 0 && !pillGroup(menuActions.get(i)).equals(pillGroup(menuActions.get(i - 1))) ? 3 * gap : gap;
    }

    private static String pillGroup(String action) {
        int colon = action.indexOf(':');
        return colon < 0 ? "" : action.substring(0, colon);
    }

    private void closeMenu() {
        menuOpen = false;
        menu.target = 0;
        aim(menuFocus, -1, false);
        heroDirty = true;
        wake();
    }

    /** Keys while the menu is open: ← → along the row, OK chooses, Back (or up, down) closes. */
    private boolean menuKey(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) {   // along the row
                    int next = alongRow(menuIndex, e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    if (next != menuIndex) moveMenuFocus(next);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    if (e.getRepeatCount() == 0 && !okDown) menuOkDown = true;
                } else {
                    if (okDown) okDown = okHeld = false;   // the release of the hold that opened the menu
                    else if (menuOkDown && !e.isCanceled()) menuAct();
                    menuOkDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down && e.getRepeatCount() == 0) {
                    boolean downward = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN;
                    int next = menuTab != null ? nearestInRow(menuRow(menuIndex) + (downward ? 1 : -1)) : -1;   // the other row of a tab's panel
                    if (next >= 0) {
                        moveMenuFocus(next);
                    } else if (menuTab != null && downward) {
                        click.move();   // below the last row: nowhere to go (Up or Back leave)
                    } else {
                        closeMenu();
                        click.move();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down && e.getRepeatCount() == 0) {
                    closeMenu();
                    click.move();
                }
                return true;
            default:
                return false;
        }
    }

    private void menuAct() {
        String a = menuActions.get(menuIndex);
        Recs.Pick p = menuPick;
        if (menuPills == WINDOWS && a.startsWith("mode:")) {
            switchList(a.equals("mode:rated"));
            return;
        }
        closeMenu();
        click.open();
        if (menuPills == WATCH_MENU) {
            if (a.equals(REMOVE_WATCH) && menuEntry != null) removeWatch(menuEntry);
            menuEntry = null;
            menuPills = NO_PILLS;
            return;
        }
        if (menuPills == RATINGS) {
            rate(p, Integer.parseInt(a.substring("rate:".length())));
            return;
        }
        if (menuPills != NO_PILLS) {   // the tab's window, language or kind, kept across restarts; the tab's name says it
            if (menuPills == WINDOWS && a.startsWith("kind:")) recs.setTabKind(menuTab, a.substring("kind:".length()));
            else if (menuPills == WINDOWS) recs.setTrendingWindow(a);
            else if (menuPills == LANGUAGES) recs.setPanLanguage(a);
            else if (menuPills == OTHERS) recs.setOtherLanguage(a.substring("lang:".length()));
            else recs.setTabKind(menuTab, a.substring("kind:".length()));
            layoutLight();
            retarget(true);
            updateCards();
            heroDirty = true;
            return;
        }
        if (a.equals(TRAILER)) {
            playTrailer(p, menuCard);
        } else if (a.equals(STREAM)) {
            openStream(p, menuCard);
        } else if (a.equals(OTHER_PICKS)) {
            otherPicks();
        } else if (a.equals(Feedback.WATCHED)) {
            openRatingMenu(p, menuCard);
        } else if (a.equals(REMIND)) {
            verdict(p, Feedback.UP);
        } else {
            verdict(p, a);
        }
    }

    /**
     * Your watchlist (UP) or Remove (DOWN) on a title, or the same one again to take it back; the row follows. On TMDB,
     * the watchlist is the watchlist and Remove a rating of ½ (Outbox: "not for me, didn't watch"); the nudge on the
     * profile stays here (Feedback).
     */
    private void verdict(Recs.Pick p, String a) {
        String key = Recs.key(p), given = Account.verdict(getContext(), key);
        boolean want = a.equals(Feedback.UP);
        if (a.equals(given)) {
            if (want) Account.want(getContext(), key, p.title, false);
            else Account.unrate(getContext(), key, p.title);
            Feedback.remove(getContext(), key);
            p.verdict = null;
            showToast((want ? Ui.tr("Taken off your watchlist") : Ui.tr("Back in your picks")) + accountNote());
        } else {
            if (want) Account.want(getContext(), key, p.title, true);
            else Account.rate(getContext(), key, p.title, Outbox.DOWN_RATING);
            Feedback.add(getContext(), recs.item(p), a);
            p.verdict = a;
            showToast((want ? Ui.tr("Added to your watchlist") : Ui.tr("Removed: it won't be picked again")) + accountNote());
        }
        recs.rerank();
        recs.syncSoon();
        heroDirty = searchDirty = true;
        updateCards();   // Remove (and Watched) leave the row, the watchlist comes first, a verdict taken back shows again
    }

    /** What the toast adds about TMDB: nothing when the token works, else why it waits. */
    private String accountNote() {
        if (recs.tokenRefused()) return " · " + Ui.tr("TMDB refused its token: run recs.py token again");
        if (!recs.hasAccount()) return " · " + Ui.tr("TMDB will get it once its token is on the TV");
        return "";
    }

    /** The entry's symbol; `given` for a verdict already given (the watchlist: a plus to add, the bookmark once on it). */
    private static String menuSymbol(String action, boolean given) {
        switch (action) {
            case Feedback.WATCHED: return "check";
            case TRAILER: return "trailer";
            case STREAM: return "play";
            case Feedback.UP: return given ? "bookmark" : "add";
            case Feedback.DOWN: return "hide";
            case Feedback.HIDE: return "hide";
            case REMIND: return "bell";
            default: return "shuffle";
        }
    }

    /** What a verdict already given says, with what OK does now. */
    private static String givenLabel(String action) {
        switch (action) {
            case Feedback.UP: return Ui.tr("On your watchlist · OK takes it off");
            case Feedback.DOWN: return Ui.tr("Removed · OK puts it back");
            case REMIND: return Ui.tr("Reminder set · OK cancels it");
            default: return Ui.trf("{label} · given", "label", menuLabel(action));
        }
    }

    private static String menuLabel(String action) {
        if (action.startsWith("lang:") && action.length() > "lang:".length() && !action.equals("lang:en")) return Ui.tr(Scorer.languageName(action.substring("lang:".length())));
        switch (action) {
            case "": return Ui.tr("All languages");
            case "all": case "r-all": return Ui.tr("All time");
            case "r-month": return Ui.tr("Month");
            case "r-year": return Ui.tr("Year");
            case "mode:trend": return Ui.tr("Trending");
            case "mode:rated": return Ui.tr("Top rated");
            case Feedback.WATCHED: return Ui.tr("Watched");
            case "rate:4": return Ui.tr("Not for me");
            case "rate:6": return Ui.tr("Okay");
            case "rate:8": return Ui.tr("Good");
            case "rate:10": return Ui.tr("Loved it");
            case "kind:": return Ui.tr("Movies and series");
            case "kind:movie": return Ui.tr("Movies");
            case "kind:tv": return Ui.tr("Series");
            case "te": case "hi": case "kn": case "ml": case "ta": case "en": return Ui.tr(Scorer.languageName(action));
            case "day": return Ui.tr("Today");
            case "week": return Ui.tr("Week");
            case "month": return Ui.tr("Month");
            case REMOVE_WATCH: return Ui.tr("Remove from Continue Watching");
            case TRAILER: return Ui.tr("Trailer");
            case STREAM: return "Stremio";   // a name, not translated
            case REMIND: return Ui.tr("Remind me");
            case "lang:": return Ui.tr("All languages");
            case "lang:en": return Ui.tr("International");   // the Upcoming tab's English-language films
            case Feedback.UP: return Ui.tr("Add to watchlist");
            case Feedback.DOWN: return Ui.tr("Remove");
            case Feedback.HIDE: return Ui.tr("Hide this title");
            default: return Ui.tr("Other picks");
        }
    }

    private static final int GIVEN = 0xFF30D158;   // the verdict you gave: Apple's green

    /** The card's menu: the screen dims around the card; under it, round glass buttons (the focused one white). */
    private void drawMenu(Canvas c) {
        float m = Ui.clamp(menu.value);
        if (m <= 0.001f || (menuPick == null && menuPills == NO_PILLS)) return;
        applyTone();
        c.save();
        // The hole in the dim frames the card (rounded corners); a tab's pills take its place in the tab bar, so no hole.
        if (menuTab == null) {
            float hole = 18 * k * 1.05f;
            menuClip.rewind();
            menuClip.addRoundRect(menuCard, hole, hole, Path.Direction.CW);
            c.clipOutPath(menuClip);
        }
        c.drawColor(Ui.alpha(0xFF000000, 0.5f * m));
        c.restore();
        int save = c.saveLayerAlpha(0, 0, w, h, Math.round(255 * m));
        float rise = (1 - m) * 20 * k;
        if (menuPills != NO_PILLS) {   // text pills: the chosen one with a check mark, the focused one white
            String chosen = chosen();
            if (menuTab != null) {   // the panel the rows sit in: dark glass under the tab bar, over the dimmed cards
                float rad = (L_BUTTON / 2 + MENU_PAD) * k;
                tmp.set(menuPanel);
                tmp.offset(0, rise + 12 * k);
                shadow.setColor(Ui.alpha(0xFF000000, 0.45f));
                shadows.draw(c, tmp, rad, DEEP_BLUR * k, shadow);
                tmp.set(menuPanel);
                tmp.offset(0, rise);
                fill.setShader(null);
                fill.setColor(Ui.alpha(INK, 0.92f));
                c.drawRoundRect(tmp, rad, rad, fill);
                scene.liquid(c, tmp, rad, INK, 0.5f, 0.01f, 12 * k, 8 * k, 0.35f, 1);
            }
            for (int i = 0; i < menuActions.size(); i++) {
                RectF r = menuRects[i];
                float f = Ui.clamp(menuFocus[i].value), s = 1 + 0.06f * f, rad = r.height() / 2 * s;
                cell.set(r.centerX() - r.width() * s / 2, r.centerY() + rise - r.height() * s / 2, r.centerX() + r.width() * s / 2, r.centerY() + rise + r.height() * s / 2);
                tmp.set(cell);
                tmp.offset(0, (6 + 8 * f) * k);
                shadow.setColor(Ui.alpha(0xFF000000, 0.3f + 0.2f * f));
                shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
                fill.setShader(null);   // the same dark base as the circles: every pill the same shade whatever is behind it
                fill.setColor(Ui.alpha(SMOKE, 0.7f * (1 - f)));
                c.drawRoundRect(cell, rad, rad, fill);
                scene.liquid(c, cell, rad, SMOKE, 0.5f, 0.01f, 12 * k, 8 * k, 0.45f, 1 - f);
                if (f > 0.01f) {
                    fill.setShader(null);
                    fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                    c.drawRoundRect(cell, rad, rad, fill);
                }
                lTip.setColor(blend(0xEBFFFFFF, L_INK, f));
                lTip.setAlpha(255);
                c.drawText(pillLabel(menuActions.get(i), chosen), cell.centerX(), cell.centerY() + Ui.halfCap(lTip), lTip);
            }
            lTip.setColor(tone(L_INK, WHITE));
            c.restoreToCount(save);
            return;
        }
        for (int i = 0; i < menuActions.size(); i++) {
            String a = menuActions.get(i);
            RectF r = menuRects[i];
            float f = Ui.clamp(menuFocus[i].value), s = 1 + 0.1f * f, rad = r.width() / 2 * s;
            float cx = r.centerX(), cy = r.centerY() + rise;
            boolean given = a.equals(menuVerdict);
            cell.set(cx - rad, cy - rad, cx + rad, cy + rad);
            tmp.set(cell);
            tmp.offset(0, (6 + 8 * f) * k);
            shadow.setColor(Ui.alpha(0xFF000000, 0.3f + 0.2f * f));
            shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
            // A dark disc under the glass: the glass bends what's behind it, and a circle over the shelf's bright rim (or a
            // light part of the picture) came out lighter than its neighbours, as if focused.
            fill.setShader(null);
            fill.setColor(Ui.alpha(SMOKE, 0.7f * (1 - f)));
            c.drawCircle(cx, cy, rad, fill);
            scene.liquid(c, cell, rad, SMOKE, 0.5f, 0.01f, 12 * k, 8 * k, 0.45f, 1 - f);
            if (f > 0.01f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.drawCircle(cx, cy, rad, fill);
            }
            Symbols.draw(getContext(), c, menuSymbol(a, given), cx, cy, 27 * k * s, given ? GIVEN : blend(0xEBFFFFFF, L_INK, f));
            if (f > 0.01f) menuTip(c, given ? givenLabel(a) : menuLabel(a), f, rise);
        }
        c.restoreToCount(save);
    }

    /** The focused entry's name, between the card and the row (under the row it ran into the shelf), white on the dim. */
    private void menuTip(Canvas c, String label, float f, float rise) {
        int was = lTip.getColor();
        lTip.setColor(0xFFFFFFFF);
        lTip.setAlpha(Math.round(255 * f));
        c.drawText(label, menuCard.centerX(), menuCard.bottom + rise + 46 * k, lTip);
        lTip.setColor(was);
    }

    /** TalkBack's long click on an app in All Apps (once per press, if the remote's hold also arrives). */
    private void a11yHold() {
        if (SystemClock.uptimeMillis() - toggledAt < 1000) return;
        if (okDown) okHeld = true;     // the remote's release must not open the app
        if (!sheetOpen && canRemoveWatch()) {
            openWatchMenu();
            return;
        }
        toggleShelf();
    }

    private boolean canToggleShelf(int i) {
        if (!sheetOpen || i >= others.size()) return false;
        HomeApps.Item it = others.get(i);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    /** Puts the focused app at the end of the shelf, or takes it off if it's there; a toast says which. */
    private void toggleShelf() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (!canToggleShelf(gridIndex)) return;
        okHeld = true;
        toggledAt = SystemClock.uptimeMillis();
        final HomeApps.Item it = others.get(gridIndex);
        final Context c = getContext().getApplicationContext();
        click.open();
        LOADER.execute(() -> {
            // On the shelf means shown there: installed and within the limit, as in Settings > Shelf.
            List<String> list = new ArrayList<>(HomeApps.shelfPackages(c));
            PackageManager pm = c.getPackageManager();
            int max = HomeApps.maxShelf(c), shown = 0;
            boolean on = false;
            for (String p : list) {
                if (shown >= max) break;
                if (HomeApps.launchIntent(pm, p) == null) continue;
                shown++;
                on |= p.equals(it.pkg);
            }
            final String text;
            list.remove(it.pkg);
            if (on) {
                text = Ui.trf("Removed from the shelf: {app}", "app", it.label);
            } else {
                if (shown >= max) {
                    handler.post(() -> showToast(Ui.tr("The shelf is full. Make room in Free My TV › Shelf.")));
                    return;
                }
                list.add(it.pkg);
                text = Ui.trf("Added to the shelf: {app}", "app", it.label);
            }
            Prefs.setShelf(list);   // here, so the next hold reads the new list
            handler.post(() -> {
                shelfEdited = true;
                showToast(text);
                reload();
            });
        });
    }

    // MARK: - Moving apps on the shelf

    private boolean canReorder() {
        if (sheetOpen || zone != Z_SHELF || shelfIndex >= shelf.size() || shelf.size() < 2) return false;
        HomeApps.Item it = shelf.get(shelfIndex);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    private void startReorder() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (reordering || !canReorder()) return;
        wake();
        reordering = reorderAnim = okHeld = true;
        reorderOrigin = new ArrayList<>(shelf);
        liftIndex = shelfIndex;
        reorderAt = SystemClock.uptimeMillis();
        moveLift.target = 1;
        click.open();
        showToast(Ui.tr("← → to move · OK to drop · Back to cancel"));
        retarget(false);    // TalkBack reads "Moving <app>, position i of n"
    }

    /** Keys while moving an app: nothing else on the home screen gets them (system keys still go to the system). */
    private boolean reorderKey(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) shiftReorder(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // The press that lifted the app is still held: its repeats and release do nothing; a new press drops it.
                if (down && e.getRepeatCount() == 0) {
                    okDown = okHeld = false;
                    dropReorder();
                } else if (!down) {
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        cancelReorder(false);
                        click.move();
                    }
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    /** Swaps the lifted app with its neighbor; both slide from where they are to their new slots. */
    private void shiftReorder(int dx) {
        int i = shelfIndex, j = i + dx;
        if (j < 0 || j >= shelf.size()) return;
        HomeApps.Item it = shelf.get(i);
        shelf.set(i, shelf.get(j));
        shelf.set(j, it);
        Spring f = tileFocus[i];
        tileFocus[i] = tileFocus[j];
        tileFocus[j] = f;
        Spring s = tileSlide[i];
        tileSlide[i] = tileSlide[j];
        tileSlide[j] = s;
        float d = tileRects[i].left - tileRects[j].left;
        tileSlide[j].value += d;
        tileSlide[i].value -= d;
        if (tileShaders.length == shelf.size()) {
            LinearGradient g = tileShaders[i];
            tileShaders[i] = tileShaders[j];
            tileShaders[j] = g;
        }
        shelfIndex = liftIndex = j;
        reorderAnim = true;
        wake();
        retarget(false);
        click.move();
    }

    /** OK: drops the app where it is and saves the shelf's new order. */
    private void dropReorder() {
        if (!reordering) return;
        reordering = false;
        moveLift.target = 0;
        boolean changed = false;
        for (int i = 0; i < shelf.size() && !changed; i++) changed = shelf.get(i) != reorderOrigin.get(i);
        reorderOrigin = null;
        click.open();
        retarget(false);
        if (!changed) return;
        final List<String> order = new ArrayList<>();
        for (HomeApps.Item it : shelf) order.add(it.pkg);
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            // The saved list may hold more than the shelf shows (apps not installed, past the limit): the shown ones
            // take their new order in the same slots, the others stay where they were.
            List<String> all = HomeApps.shelfPackages(c), out = new ArrayList<>(all);
            int next = 0;
            for (int s = 0; s < out.size() && next < order.size(); s++) {
                if (order.contains(out.get(s))) out.set(s, order.get(next++));
            }
            if (next < order.size()) {
                out = new ArrayList<>(order);
                for (String p : all) if (!order.contains(p)) out.add(p);
            }
            final List<String> save = out;
            handler.post(() -> Prefs.setShelf(save));
        });
    }

    /** Back, or leaving the home screen: the original order comes back (sliding, unless `snap`). */
    private void cancelReorder(boolean snap) {
        if (!reordering && !reorderAnim) return;
        if (reordering) {
            reordering = false;
            moveLift.target = 0;
            int n = shelf.size();
            if (reorderOrigin != null && reorderOrigin.size() == n) {
                HomeApps.Item lifted = shelf.get(shelfIndex);
                Spring[] focus = new Spring[n], slide = new Spring[n];
                LinearGradient[] shaders = tileShaders.length == n ? new LinearGradient[n] : tileShaders;
                for (int a = 0; a < n; a++) {
                    int b = shelf.indexOf(reorderOrigin.get(a));
                    focus[a] = tileFocus[b];
                    slide[a] = tileSlide[b];
                    slide[a].value += tileRects[b].left - tileRects[a].left;
                    if (shaders != tileShaders) shaders[a] = tileShaders[b];
                }
                shelf = reorderOrigin;
                tileFocus = focus;
                tileSlide = slide;
                tileShaders = shaders;
                shelfIndex = liftIndex = shelf.indexOf(lifted);
            }
            reorderOrigin = null;
            retarget(snap);
        }
        if (snap) {
            moveLift.snap(0);
            for (Spring s : tileSlide) s.snap(0);
            wiggle = 0;
            liftIndex = -1;
            reorderAnim = false;
            tilesDirty = true;
        } else {
            wake();
        }
    }

    /** One frame of the move: the slides, the lift and the wiggle. Returns true while any of them moves. */
    private boolean stepReorder(float dt, long up) {
        boolean moving = step(tileSlide, dt) | moveLift.step(dt);
        if (reordering || moveLift.value > 0.001f) {
            wiggle = WIGGLE_DEG * (float) Math.sin((up - reorderAt) * (2 * Math.PI / WIGGLE_MS));
            moving = true;
        } else {
            wiggle = 0;
        }
        reorderAnim = moving;
        return moving;
    }

    // MARK: - Info page (OK on a title): synopsis, who made it, ratings, trailer, Watch on and your verdict, over everything

    private void openInfo(Recs.Pick p) {
        infoPick = p;
        infoIndex = 0;   // the first entry: an app of the TV's when it has one, so OK again plays it there
        infoActions.clear();
        infoItems.clear();
        // Where to watch first (the TV's apps, then Stremio), then the trailer, then your verdict.
        if (p.pkg != null) {
            infoActions.add(p.app);
            infoItems.add(HomeApps.service(getContext(), p.app, p.pkg));
            for (String[] alt : p.alts) {
                if (infoActions.size() >= MENU_MAX - 5) break;   // room for Stremio, the trailer and three verdicts
                infoActions.add(alt[0]);
                infoItems.add(HomeApps.service(getContext(), alt[0], alt[1]));
            }
        }
        if (streamable(p)) {
            infoActions.add(STREAM);
            infoItems.add(null);
        }
        if (p.trailer != null) {
            infoActions.add(TRAILER);
            infoItems.add(null);
        }
        if (p.upcoming()) {
            infoActions.add(REMIND);
            infoItems.add(null);
        } else {
            infoActions.add(Feedback.WATCHED);
            infoItems.add(null);
            infoActions.add(Feedback.UP);
            infoItems.add(null);
            if (!searchOpen) {   // Remove: only a title in the picks (a search result isn't in a row)
                infoActions.add(Feedback.DOWN);
                infoItems.add(null);
            }
        }
        layoutInfo();
        infoOpen = true;
        info.target = 1;
        for (Spring sp : infoFocus) sp.snap(0);
        aim(infoFocus, infoIndex, false);   // the ring on the focused entry (aimed at slot 0, the first Right looked like a skip)
        recs.backdrop(p);   // its photo, if not ready yet
        click.open();
        announceForAccessibility(p.title + ". " + infoMeta + ". " + p.overview);
        wake();
        toastLayer.invalidate();
        spoken.focusMoved();
    }

    private void closeInfo() {
        infoOpen = false;
        info.target = 0;
        aim(infoFocus, -1, false);
        click.move();
        wake();
        spoken.focusMoved();
    }

    /** The texts and places of the page (once, when it opens or the screen size changes: nothing is built per frame). */
    private void layoutInfo() {
        Recs.Pick p = infoPick;
        if (p == null || iTitle == null) return;
        float left = MARGIN * k, width = (I_COLUMN - MARGIN) * k;
        String title = p.title == null ? "" : p.title;
        infoTitle = StaticLayout.Builder.obtain(title, 0, title.length(), iTitle, Math.max(1, Math.round(width)))
                .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 0.96f).setIncludePad(false).build();
        List<String> parts = new ArrayList<>();
        if (p.year > 0) parts.add(String.valueOf(p.year));
        if (p.kind.equals("tv")) parts.add(Ui.tr("Series"));
        if (!p.genres.isEmpty()) parts.add(p.genres);
        if (!p.length.isEmpty()) parts.add(p.length);
        if (p.pkg == null) {   // not on the TV's apps (the Others tab, a search result): where it is, or when it comes
            long now = System.currentTimeMillis();
            if (p.inCinemas > now) parts.add(Ui.trf("In cinemas {date}", "date", shortDate(p.inCinemas)));
            if (p.streamingAt > now) parts.add(Ui.trf("Streaming {date}", "date", shortDate(p.streamingAt)));
            if (p.elsewhere != null) parts.add(Ui.trf("On {service}", "service", p.elsewhere));
            else if (p.rentOn != null) parts.add(Ui.trf("Rent on {service}", "service", p.rentOn));
        }
        fitPaint.set(iMeta);
        float metaW = width - (p.certification.isEmpty() ? 0 : iMeta.measureText(p.certification) + 50 * k);
        infoMeta = TextUtils.ellipsize(String.join(" · ", parts), fitPaint, metaW, TextUtils.TruncateAt.END).toString();
        String director = p.director.isEmpty() ? "" : (p.kind.equals("movie") ? Ui.trf("Directed by {name}", "name", p.director) : Ui.trf("Created by {name}", "name", p.director));
        infoDirector = TextUtils.ellipsize(director, fitPaint, width, TextUtils.TruncateAt.END).toString();
        infoCast = TextUtils.ellipsize(p.cast.isEmpty() ? "" : Ui.trf("Cast: {names}", "names", p.cast), fitPaint, width, TextUtils.TruncateAt.END).toString();
        for (int i = 0; i < infoActions.size(); i++) infoLabels[i] = infoLabel(i);
        infoPills[0] = p.scoreText.isEmpty() ? "" : p.scoreText + " TMDB";
        infoPills[1] = p.imdbText.isEmpty() ? "" : p.imdbVotes > 0 ? p.imdbText + " (" + p.votesText() + ")" : p.imdbText;
        infoPills[2] = p.tomatoText.isEmpty() ? "" : p.tomatoText + (p.certifiedFresh ? " " + Ui.tr("Certified Fresh") : " Rotten Tomatoes");
        infoPills[3] = p.jwText.isEmpty() ? "" : p.jwText.replace("JW", "JustWatch");
        String overview = p.overview.isEmpty() ? Ui.tr("No synopsis yet.") : p.overview;
        infoOverview = StaticLayout.Builder.obtain(overview, 0, overview.length(), iBody, Math.max(1, Math.round(width)))
                .setMaxLines(7).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 1.18f).setIncludePad(false).build();
        infoImage.set(I_IMAGE_L * k, I_IMAGE_T * k, (I_IMAGE_L + I_IMAGE_W) * k, (I_IMAGE_T + I_IMAGE_W * 9 / 16) * k);
        // The row: round buttons (trailer, verdicts) and the services' tiles, left aligned, on one center line.
        float x = left, cy = I_ROW_CY * k, b = L_BUTTON * k, gap = L_BUTTON_GAP * k;
        for (int i = 0; i < infoActions.size(); i++) {
            boolean tile = infoItems.get(i) != null;
            float w1 = (tile ? MENU_TILE_W : L_BUTTON) * k, h1 = (tile ? MENU_TILE_H : L_BUTTON) * k;
            infoRects[i].set(x, cy - h1 / 2, x + w1, cy + h1 / 2);
            x += w1 + gap;
        }
        for (String symbol : new String[] {"trailer", "play", "add", "bookmark", "hide", "check"}) {
            Symbols.warm(getContext(), symbol, 27 * k);
            Symbols.warm(getContext(), symbol, 27 * k * 1.1f);
        }
    }

    private static String shortDate(long at) {
        return java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, Ui.locale()).format(new java.util.Date(at));
    }

    private String infoLabel(int i) {
        String a = infoActions.get(i);
        if (infoItems.get(i) != null) return Ui.trf("Watch on {service}", "service", infoItems.get(i).label);
        if ((a.equals(Feedback.UP) || a.equals(REMIND)) && Feedback.UP.equals(infoPick.verdict)) return givenLabel(a);
        if (a.equals(Feedback.DOWN) && Feedback.DOWN.equals(infoPick.verdict)) return givenLabel(a);
        if (a.equals(Feedback.WATCHED) && Account.watchedRating(getContext(), Recs.key(infoPick)) > 0) return Ui.trf("Watched · {n}/10", "n", Account.watchedRating(getContext(), Recs.key(infoPick)));
        return menuLabel(a);
    }

    private boolean infoKey(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) {
                    int next = wrap(infoIndex, e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1, infoActions.size());   // the row wraps
                    if (next != infoIndex) {
                        infoIndex = next;
                        aim(infoFocus, infoIndex, false);
                        click.move();
                        announceForAccessibility(infoLabel(infoIndex));
                        wake();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    if (e.getRepeatCount() == 0 && !okDown) menuOkDown = true;
                } else {
                    if (okDown) okDown = okHeld = false;
                    else if (menuOkDown && !e.isCanceled()) infoAct();
                    menuOkDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down && e.getRepeatCount() == 0) closeInfo();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            default:
                return false;
        }
    }

    private void infoAct() {
        if (infoIndex >= infoActions.size()) return;
        String a = infoActions.get(infoIndex);
        Recs.Pick p = infoPick;
        RectF r = infoRects[infoIndex];
        click.open();
        if (a.equals(TRAILER)) {
            playTrailer(p, r);
        } else if (a.equals(STREAM)) {
            openStream(p, r);
        } else if (a.equals(Feedback.WATCHED)) {
            openRatingMenu(p, r);
        } else if (a.equals(Feedback.UP) || a.equals(Feedback.DOWN) || a.equals(REMIND)) {
            verdict(p, a.equals(REMIND) ? Feedback.UP : a);
            for (int i = 0; i < infoActions.size(); i++) infoLabels[i] = infoLabel(i);   // "· given" follows
            toastLayer.invalidate();
        } else {   // one of its services: that app, on the title's page when there is a link
            Watching.launched(getContext(), p, p.pkgOf(a), p.linkOf(a));
            HomeApps.Item it = HomeApps.app(p.pkgOf(a), Recs.SERVICE_NAME.get(a), p.linkOf(a));
            boolean opened = HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top), Math.round(r.width()), Math.round(r.height()));
            if (!opened) showToast(Ui.trf("Couldn't open {app}.", "app", it.label));
        }
    }

    /** One pill starting at `left` (none when empty); returns where the next one starts. */
    private float pillAt(Canvas c, String text, float left, float top, float h, float pad) {
        if (text == null || text.isEmpty()) return left;
        float w = iPill.measureText(text) + 2 * pad;
        tmp.set(left, top, left + w, top + h);
        c.drawRoundRect(tmp, h / 2, h / 2, scorePill);
        c.drawText(text, left + pad, tmp.centerY() + Ui.halfCap(iPill), iPill);
        return tmp.right + 10 * k;
    }

    private void drawInfo(Canvas c) {
        float m = Ui.clamp(info.value);
        Recs.Pick p = infoPick;
        if (m <= 0.001f || p == null || infoTitle == null) return;
        c.drawColor(Ui.alpha(0xFF000000, 0.86f * m));
        int save = c.saveLayerAlpha(0, 0, w, h, Math.round(255 * m));
        c.translate(0, (1 - m) * 24 * k);
        // The photo, top right: the title's own backdrop (the card's wide copy) or, for a search result, its picture.
        Backdrop b = recs.backdrop(p);
        Recs.Thumb t = b == null || b.wide == null ? recs.thumb(p) : null;
        float r = 22 * k;
        tmp.set(infoImage);
        tmp.offset(0, 18 * k);
        shadowDeep.setColor(0x80000000);
        shadows.draw(c, tmp, r, DEEP_BLUR * k, shadowDeep);
        if (b != null && b.wide != null) roundImageCover(c, b.wide, b.wideShader, infoImage, r);
        else if (t != null) roundImageCover(c, t.bitmap, t.shader, infoImage, r);
        else {
            fill.setShader(null);
            fill.setColor(0xFF2C2C2E);
            c.drawRoundRect(infoImage, r, r, fill);
        }
        border.setColor(0x1FFFFFFF);
        c.drawRoundRect(infoImage, r, r, border);
        // The words, in the left column.
        float left = MARGIN * k, y = 112 * k;
        c.save();
        c.translate(left, y);
        infoTitle.draw(c);
        c.restore();
        y += infoTitle.getHeight() + 30 * k;
        if (!infoMeta.isEmpty()) {
            c.drawText(infoMeta, left, y + Ui.cap(iMeta), iMeta);
            if (!p.certification.isEmpty()) {   // the age rating in a thin box, after the line
                float cx = left + iMeta.measureText(infoMeta) + 22 * k, ch = Ui.cap(iMeta) + 14 * k, cw = iMeta.measureText(p.certification) + 20 * k;
                tmp.set(cx, y - 7 * k, cx + cw, y - 7 * k + ch);
                border.setColor(0x80FFFFFF);
                c.drawRoundRect(tmp, 6 * k, 6 * k, border);
                c.drawText(p.certification, cx + 10 * k, y + Ui.cap(iMeta), iMeta);
            }
            y += Ui.cap(iMeta) + 34 * k;
        }
        float x = left, ph = 38 * k;
        for (String pill : infoPills) x = pillAt(c, pill, x, y, ph, 14 * k);
        if (x > left) y += ph + 34 * k;
        c.save();
        c.translate(left, y);
        infoOverview.draw(c);
        c.restore();
        y += infoOverview.getHeight() + 30 * k;
        if (!infoDirector.isEmpty()) {
            c.drawText(infoDirector, left, y + Ui.cap(iMeta), iMeta);
            y += Ui.cap(iMeta) + 20 * k;
        }
        if (!infoCast.isEmpty()) c.drawText(infoCast, left, y + Ui.cap(iMeta), iMeta);
        // The row: trailer, the services, your verdict.
        for (int i = 0; i < infoActions.size(); i++) {
            String a = infoActions.get(i);
            RectF rr = infoRects[i];
            float f = Ui.clamp(infoFocus[i].value);
            HomeApps.Item it = infoItems.get(i);
            if (it != null) {
                drawCard(c, it, rr, 14 * k, f, 0.1f);
            } else {
                float s = 1 + 0.1f * f, rad = rr.width() / 2 * s, cx = rr.centerX(), cy = rr.centerY();
                boolean given = a.equals(p.verdict);
                cell.set(cx - rad, cy - rad, cx + rad, cy + rad);
                tmp.set(cell);
                tmp.offset(0, (6 + 8 * f) * k);
                shadow.setColor(Ui.alpha(0xFF000000, 0.3f + 0.2f * f));
                shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
                fill.setShader(null);
                fill.setColor(Ui.alpha(SMOKE, 0.7f * (1 - f)));
                c.drawCircle(cx, cy, rad, fill);
                scene.liquid(c, cell, rad, SMOKE, 0.5f, 0.01f, 12 * k, 8 * k, 0.45f, 1 - f);
                if (f > 0.01f) {
                    fill.setShader(null);
                    fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                    c.drawCircle(cx, cy, rad, fill);
                }
                Symbols.draw(getContext(), c, menuSymbol(a, given), cx, cy, 27 * k * s, given ? GIVEN : blend(0xEBFFFFFF, L_INK, f));
            }
            if (f > 0.01f && infoLabels[i] != null) {
                iLabel.setAlpha(Math.round(255 * f));
                float half = iLabel.measureText(infoLabels[i]) / 2;
                c.drawText(infoLabels[i], Math.max(left + half, rr.centerX()), rr.bottom + (44 + 4 * f) * k, iLabel);
                iLabel.setAlpha(255);
            }
        }
        c.restoreToCount(save);
    }

    // MARK: - Search page: the remote's Assistant button (or the magnifier left of the tabs) opens it, by voice or
    // with the TV's keyboard; the results open the title in its app, like the cards

    private void layoutSearch() {
        float fw = S_FIELD_W * k, fh = S_FIELD_H * k, b = L_BUTTON * k, gap = 20 * k;
        float left = (w - fw - gap - b) / 2, top = S_FIELD_TOP * k;
        fieldRect.set(left, top, left + fw, top + fh);
        micRect.set(fieldRect.right + gap, fieldRect.centerY() - b / 2, fieldRect.right + gap + b, fieldRect.centerY() + b / 2);
        clearRect.set(micRect.right + gap, micRect.top, micRect.right + gap + b, micRect.bottom);
        snap(fieldRect);
        snap(micRect);
        snap(clearRect);
    }

    /** Opens the page (over the home screen, like All Apps); `voice` also starts listening at once. */
    private void openSearch(boolean voice) {
        wake();
        if (menuOpen) closeMenu();
        if (reordering) cancelReorder(true);
        if (drawerOpen) closeDrawer();
        if (sheetOpen) closeSheet();
        sheetIsSearch = true;
        searchOpen = true;
        sZone = S_FIELD;
        sIndex = 0;
        sScroll.snap(0);
        updateResults();
        retargetSearch(true);
        retarget(false);
        sheet.target = 1;
        searchDirty = true;
        click.open();
        spoken.focusMoved();
        if (voice) startVoice();
    }

    private void closeSearch() {
        hideKeyboard();
        searchOpen = false;
        jumpToResults = false;
        sheet.target = 0;
        retarget(false);
        spoken.focusMoved();
    }

    private void retargetSearch(boolean snap) {
        if (sZone == S_CLEAR && !canClear()) sZone = S_FIELD;   // nothing left to clear: the button is gone
        fieldFocus.target = searchOpen && sZone == S_FIELD ? 1 : 0;
        micFocus.target = searchOpen && sZone == S_MIC ? 1 : 0;
        clearFocus.target = searchOpen && sZone == S_CLEAR ? 1 : 0;
        if (snap) {
            fieldFocus.snap(fieldFocus.target);
            micFocus.snap(micFocus.target);
            clearFocus.snap(clearFocus.target);
        }
        aim(resultFocus, searchOpen && sZone == S_RESULTS ? sIndex : -1, snap);
        resultsLit.target = sZone == S_RESULTS ? 1 : 0.65f;
        if (snap) resultsLit.snap(resultsLit.target);
        int rows = Math.max(1, (results.size() + S_COLS - 1) / S_COLS);
        int visible = Math.min(rows, S_ROWS);
        int r = sZone == S_RESULTS ? sIndex / S_COLS : 0;
        float target = sScroll.target;
        if (r < target) target = r;
        else if (r > target + visible - 1) target = r - visible + 1;
        sScroll.target = Math.max(0, Math.min(rows - visible, target));
        if (snap) sScroll.snap(sScroll.target);
        searchDirty = true;
        spoken.focusMoved();
    }

    /** Recs has new results (or a result's details or picture): the grid follows; after a voice search, the focus too. */
    private void updateResults() {
        if (!sheetIsSearch) return;
        List<Recs.Pick> list = recs.results();
        if (!samePicks(list, results) || resultFocus.length != list.size()) {
            Spring[] focus = new Spring[list.size()];
            for (int i = 0; i < focus.length; i++) focus[i] = new Spring(0.3f, 0.82f, 0);
            resultFocus = focus;
            resultLabels = resultMetas = null;
            if (sIndex >= list.size()) sIndex = Math.max(0, list.size() - 1);
            if (sZone == S_RESULTS && list.isEmpty()) sZone = S_FIELD;
        } else {
            resultMetas = null;   // details came in
        }
        results = list;
        if (jumpToResults && !results.isEmpty() && searchOpen) {
            jumpToResults = false;
            sZone = S_RESULTS;
            sIndex = 0;
            announceForAccessibility(Ui.plural("plural|one:{n} result|other:{n} results", results.size()));
        }
        retargetSearch(false);
    }

    /** The result's card rectangle (in `out`), as drawn at rest with the grid scrolled where it is heading. */
    private RectF resultRect(int i, RectF out) {
        float gridWidth = S_COLS * S_W + (S_COLS - 1) * S_GAP;
        float x = ((1920 - gridWidth) / 2 + (i % S_COLS) * (S_W + S_GAP)) * k;
        float y = (S_GRID_TOP + (i / S_COLS - sScroll.target) * S_ROW) * k;
        out.set(x, y, x + S_W * k, y + S_H * k);
        return out;
    }

    /** Keys on the page: arrows among the field, the mic and the results; OK types, listens or opens; Back closes. */
    private boolean searchKey(KeyEvent e, boolean down) {
        if (listening && down && e.getKeyCode() == KeyEvent.KEYCODE_BACK) {   // Back while listening: stop, stay on the page
            stopListening();
            return true;
        }
        int code = e.getKeyCode();
        if (down && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER && code != KeyEvent.KEYCODE_NUMPAD_ENTER) {
            holdArmed = false;
            handler.removeCallbacks(holdStart);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) moveSearch(code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0,
                        code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        okHeld = false;
                        handler.removeCallbacks(holdStart);
                        holdArmed = canRateResult();
                        if (holdArmed) handler.postDelayed(holdStart, HOLD_MS);
                    } else if (okDown && holdArmed && e.isLongPress()) {
                        hold();
                    }
                } else {
                    holdArmed = false;
                    handler.removeCallbacks(holdStart);
                    if (okDown && !okHeld && !e.isCanceled() && spoken.keyClick()) searchActivate();
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        closeSearch();
                        click.move();
                    }
                    backDown = false;
                }
                return true;
            default:
                return false;   // letters from a keyboard go on to the field
        }
    }

    private void moveSearch(int dx, int dy) {
        int n = results.size();
        int zone = sZone, index = sIndex;
        if (sZone == S_RESULTS) {
            int r = sIndex / S_COLS, col = sIndex % S_COLS, rows = (n + S_COLS - 1) / S_COLS;
            if (dx != 0) {
                int first = r * S_COLS, last = Math.min(n, first + S_COLS) - 1;   // along the row, wrapping at its ends
                index = first + wrap(sIndex - first, dx, last - first + 1);
            } else if (dy < 0 && r == 0) {
                zone = col >= S_COLS - 1 ? S_MIC : S_FIELD;
            } else {
                int targetRow = r + dy;
                if (targetRow >= 0 && targetRow < rows) index = Math.min(n - 1, targetRow * S_COLS + col);
            }
        } else if (dy > 0) {
            if (n == 0) return;
            zone = S_RESULTS;
            index = sZone == S_FIELD ? 0 : Math.min(n - 1, S_COLS - 1);
        } else if (dx != 0) {   // the field, the mic, then Clear while there is something to clear: a row that wraps
            int[] row = canClear() ? new int[] {S_FIELD, S_MIC, S_CLEAR} : new int[] {S_FIELD, S_MIC};
            int at = sZone == S_CLEAR ? row.length - 1 : sZone;
            zone = row[wrap(at, dx, row.length)];
        }
        if (zone == sZone && index == sIndex) return;
        sZone = zone;
        sIndex = index;
        retargetSearch(false);
        click.move();
    }

    /** Something to clear: words in the field or results under it. */
    private boolean canClear() {
        return field.getText().length() > 0 || !results.isEmpty();
    }

    /** Clear: the words and the results go (no deleting letter by letter), the focus lands on the field; the mic listens again on OK. */
    private void clearSearch() {
        hideKeyboard();
        if (listening) stopListening();
        handler.removeCallbacks(runSearch);
        field.setText("");
        handler.removeCallbacks(runSearch);   // the watcher queued one
        recs.search("");
        sZone = S_FIELD;
        sIndex = 0;
        retargetSearch(false);
        click.move();
        announceForAccessibility(Ui.tr("Search for a film or a show"));
        searchDirty = true;
        wake();
    }

    private boolean canRateResult() {
        return searchOpen && sZone == S_RESULTS && sIndex < results.size() && results.get(sIndex).detailed;
    }

    /** OK on the page: the field opens the keyboard, the mic listens, a result opens the title's page. */
    private void searchActivate() {
        if (sZone == S_FIELD) {
            showKeyboard();
            return;
        }
        if (sZone == S_MIC) {
            startVoice();
            return;
        }
        if (sZone == S_CLEAR) {
            clearSearch();
            return;
        }
        if (sIndex >= results.size()) return;
        Recs.Pick p = results.get(sIndex);
        if (!p.detailed) return;   // its app isn't known yet
        openInfo(p);   // its page: you pick where to watch it (Stremio included), even with one app
    }

    /**
     * Google's Speech Recognition and Synthesis first (it listens as soon as it opens), then Google TV's own voice input
     * (the Assistant app's: it only listens once the remote's Assistant button is pressed, so pressing OK on the mic
     * and speaking gave nothing).
     */
    // MARK: - Voice (the Search page's mic): Android's speech recognizer in-app first (the TV's default service, Google's
    // Speech Services), "Listening…" in the field, no screen over the home. When it can't record (ERROR_AUDIO: on this
    // TCL the whole audio input path was dead until a restart, Google's own hotword included), Google's Speech Services
    // screen takes over (RECOGNIZERS); its answer comes back to activityResult. Google TV's own recognition service
    // (katniss) is never bound: it loops on AudioRecord -38 here and never answers.

    private static final int REQ_MIC = 42, REQ_VOICE = 41;
    /**
     * Google's Speech Services screen first: it is the one that hears the remote's microphone here (verified by the user).
     * Google TV's own voice input (katniss) records from the TV's mics, which this TCL has none of: it fails, silently.
     */
    private static final String[] RECOGNIZERS = {"com.google.android.tts", "com.google.android.katniss"};
    private android.speech.SpeechRecognizer speech;
    private boolean listening;
    /** Which recognition service `speech` binds: only the TV's default (Settings: voice_recognition_service). */
    private int speechService;
    private static final android.content.ComponentName[] SPEECH_SERVICES = {null};
    private String heardSoFar = "";

    private void startVoice() {
        hideKeyboard();
        if (listening) {
            stopListening();
            return;
        }
        Context ctx = getContext();
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(ctx)) {
            startVoiceIntent();
            return;
        }
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[] {android.Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        speechService = 0;
        listen();
    }

    /** HomeActivity: the microphone permission's answer. */
    void permissionResult(int request, boolean granted) {
        if (request != REQ_MIC) return;
        if (granted) listen();
        else noVoice(Ui.tr("Without the microphone permission, type the name instead."));
    }

    /** Voice can't be had: says why, and the keyboard takes over, the page staying open. */
    private void noVoice(String why) {
        listening = false;
        searchDirty = true;
        showToast(why);
        if (searchOpen) {
            sZone = S_FIELD;
            retargetSearch(false);
            showKeyboard();
        }
        wake();
    }

    private void listen() {
        if (speech == null) {
            android.content.ComponentName service = SPEECH_SERVICES[speechService];
            speech = service == null ? android.speech.SpeechRecognizer.createSpeechRecognizer(getContext())
                    : android.speech.SpeechRecognizer.createSpeechRecognizer(getContext(), service);
            speech.setRecognitionListener(new android.speech.RecognitionListener() {
                @Override public void onReadyForSpeech(android.os.Bundle params) {}
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onEvent(int eventType, android.os.Bundle params) {}

                @Override public void onPartialResults(android.os.Bundle b) {
                    java.util.ArrayList<String> words = b.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
                    if (words == null || words.isEmpty() || words.get(0).trim().isEmpty()) return;
                    heardSoFar = words.get(0).trim();
                    searchDirty = true;
                    wake();
                }

                @Override public void onResults(android.os.Bundle b) {
                    if (!listening) return;   // cancelled (Back, Clear): a late answer changes nothing
                    listening = false;
                    searchDirty = true;
                    java.util.ArrayList<String> words = b.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
                    String q = words == null || words.isEmpty() ? heardSoFar : words.get(0).trim();
                    if (q.isEmpty()) showToast(Ui.tr("Didn't catch that. Try again, or type the name."));
                    else heard(q);
                    wake();
                }

                @Override public void onError(int code) {
                    if (!listening) return;   // cancelled (Back, Clear): the recognizer's error for that is no reason for Google's screen
                    listening = false;
                    searchDirty = true;
                    wake();
                    switch (code) {
                        case android.speech.SpeechRecognizer.ERROR_NO_MATCH:
                        case android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                            if (!heardSoFar.isEmpty()) heard(heardSoFar);   // the final answer never came, but words did
                            else showToast(Ui.tr("Didn't catch that. Try again, or type the name."));
                            return;
                        case android.speech.SpeechRecognizer.ERROR_NETWORK:
                        case android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                            showToast(Ui.tr("Voice search needs the internet. Type the name instead."));
                            return;
                        default:   // the service can't record (3: the TV's microphone is off for apps) or is broken
                            if (speechService + 1 < SPEECH_SERVICES.length) {   // the next service: the Assistant's own may do better
                                AppLog.w("voice: recognizer error " + code + " from service " + speechService + "; trying the next");
                                speech.destroy();
                                speech = null;
                                speechService++;
                                listen();
                                return;
                            }
                            AppLog.w("voice: recognizer error " + code + ": no service can hear the TV's microphone; Google TV's voice input instead");
                            speech.destroy();
                            speech = null;
                            startVoiceIntent();
                    }
                }
            });
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getContext().getPackageName())
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        listening = true;
        heardSoFar = "";
        searchDirty = true;
        click.open();
        announceForAccessibility(Ui.tr("Listening…"));
        wake();
        try {
            speech.startListening(intent);
        } catch (RuntimeException e) {
            AppLog.w("voice: " + e.getMessage() + "; Google TV's voice input instead");
            startVoiceIntent();
        }
    }

    /**
     * Google TV's voice input over the home: it takes the remote's microphone when its button is pressed and answers
     * to activityResult. One recognizer, named: with two on the TV, Android asked which to use every time.
     */
    private void startVoiceIntent() {
        listening = false;
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, Ui.tr("Say the name of a film or a show"))
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        for (String pkg : RECOGNIZERS) {
            intent.setPackage(pkg);
            if (!getContext().getPackageManager().queryIntentActivities(intent, 0).isEmpty()) break;
            intent.setPackage(null);
        }
        try {
            activity.startActivityForResult(intent, REQ_VOICE);
            click.open();
        } catch (android.content.ActivityNotFoundException e) {
            noVoice(Ui.tr("This TV has no voice input: type the name instead."));
        }
    }

    /** HomeActivity: Google TV's voice input answered (or was dismissed). */
    void activityResult(int request, int result, Intent data) {
        if (request != REQ_VOICE) return;
        java.util.ArrayList<String> words = data == null ? null : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (result != Activity.RESULT_OK || words == null || words.isEmpty() || words.get(0).trim().isEmpty()) {
            if (result != Activity.RESULT_CANCELED) showToast(Ui.tr("Didn't catch that. Try again, or type the name."));
            return;
        }
        heard(words.get(0).trim());
    }

    private void stopListening() {
        if (speech != null) speech.cancel();
        listening = false;
        searchDirty = true;
        wake();
    }

    /** The field's grey line: what's being heard while listening, else the invitation. */
    private String fieldHint() {
        if (!listening) return Ui.tr("Search for a film or a show");
        return heardSoFar.isEmpty() ? Ui.tr("Listening…") : heardSoFar;
    }

    /** What was said: into the field, searched at once. */
    private void heard(String q) {
        AppLog.w("voice search: " + q);
        if (!searchOpen) openSearch(false);
        handler.removeCallbacks(runSearch);
        field.setText(q);
        field.setSelection(q.length());
        handler.removeCallbacks(runSearch);   // the watcher queued one: run it now instead
        recs.search(q);
        jumpToResults = true;
        searchDirty = true;
        wake();
    }

    private void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        editing = true;
        field.requestFocus();
        imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        searchDirty = true;
        wake();
    }

    private void hideKeyboard() {
        if (!editing) return;
        editing = false;
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
        requestFocus();
        searchDirty = true;
        wake();
    }

    /** What the keyboard typed, a moment after the last key (TMDB is asked once per pause, not per letter). */
    private void queryChanged() {
        handler.removeCallbacks(runSearch);
        handler.postDelayed(runSearch, 450);
        searchDirty = true;
        wake();
    }

    /** The TV's keyboard types here. Invisible (alpha 0): the page draws the text itself. Back closes the keyboard. */
    private final class Field extends EditText {
        Field(Context c) {
            super(c);
            setSingleLine(true);
            setInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
            setHint(Ui.tr("Search for a film or a show"));
            setAlpha(0f);
            setBackground(null);
            setCursorVisible(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // Spoken reads the drawn field
            addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { queryChanged(); }
                @Override public void afterTextChanged(Editable s) {}
            });
            setOnEditorActionListener((v, action, event) -> {
                hideKeyboard();
                handler.removeCallbacks(runSearch);
                recs.search(getText().toString());
                jumpToResults = true;
                return true;
            });
        }

        @Override public boolean onKeyPreIme(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_BACK && editing) {
                if (event.getAction() == KeyEvent.ACTION_UP) hideKeyboard();
                return true;
            }
            return super.onKeyPreIme(keyCode, event);
        }
    }

    /** The page: the field (the query, or a hint), the mic, a line on how the search is going, the results. */
    private void drawSearch(Canvas c) {
        c.drawColor(0xA8000000);
        float shift = sScroll.value * S_ROW * k;
        // The field: a smoked glass capsule, white while focused (the tvOS focus), the magnifier at its left end.
        float f = Ui.clamp(fieldFocus.value), s = 1 + 0.02f * f, r = fieldRect.height() / 2 * s;
        cell.set(fieldRect.centerX() - fieldRect.width() * s / 2, fieldRect.centerY() - fieldRect.height() * s / 2,
                fieldRect.centerX() + fieldRect.width() * s / 2, fieldRect.centerY() + fieldRect.height() * s / 2);
        tmp.set(cell);
        tmp.offset(0, (6 + 8 * f) * k);
        shadow.setColor(Ui.alpha(0xFF000000, 0.3f + 0.2f * f));
        shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        scene.glass(c, cell, r, SMOKE, 0.55f, 0, 0, 0.02f, 1 - f);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, f));
            c.drawRoundRect(cell, r, r, fill);
        }
        float pad = 30 * k, icon = 27 * k, iconW = Symbols.width(getContext(), "search", icon);
        int ink = blend(0xEBFFFFFF, L_INK, f);
        Symbols.draw(getContext(), c, "search", cell.left + pad + iconW / 2, cell.centerY(), icon, editing ? ink : blend(0x99FFFFFF, L_DIM, f));
        String query = field.getText().toString();
        float textX = cell.left + pad + iconW + 20 * k, textRight = cell.right - pad;
        if (query.isEmpty()) {
            sHint.setColor(blend(0x80FFFFFF, L_DIM, f));
            c.drawText(fieldHint(), textX, cell.centerY() + Ui.halfCap(sHint), sHint);
        } else {
            sQuery.setColor(ink);
            fitPaint.set(sQuery);
            String shown = TextUtils.ellipsize(query, fitPaint, textRight - textX - 6 * k, TextUtils.TruncateAt.START).toString();
            c.drawText(shown, textX, cell.centerY() + Ui.halfCap(sQuery), sQuery);
            if (editing) {   // the caret, where the keyboard will add letters
                float cx = textX + sQuery.measureText(shown) + 3 * k;
                fill.setShader(null);
                fill.setColor(ink);
                c.drawRect(cx, cell.centerY() - 18 * k, cx + 2.5f * k, cell.centerY() + 18 * k, fill);
            }
        }
        drawRoundButton(c, "mic", listening ? Ui.tr("Listening…") : Ui.tr("Search by voice"), micRect, Ui.clamp(Math.max(micFocus.value, listening ? 1 : 0)), Ui.clamp(micFocus.value), 27 * k);
        if (canClear()) drawRoundButton(c, "close", Ui.tr("Clear the search"), clearRect, Ui.clamp(clearFocus.value), 25 * k);
        // How it is going, under the field.
        String status = null;
        if (recs.searching()) status = Ui.tr("Searching…");
        else if (!recs.searchError().isEmpty()) status = Ui.trf("Couldn't search now: {error}", "error", recs.searchError());
        else if (results.isEmpty() && !recs.searchQuery().isEmpty() && recs.searchQuery().equals(query.trim())) status = Ui.trf("Nothing found for “{query}”", "query", query.trim());
        else if (results.isEmpty() && query.trim().isEmpty()) status = Ui.tr("Type the name, or press the mic and say it.");
        if (status != null) c.drawText(status, w / 2f, (S_FIELD_TOP + S_FIELD_H + 56) * k, gridLabel);
        // The results: cards like All Apps, the ones the TV can't play dimmed, the title and its details under each.
        int n = results.size();
        if (resultLabels == null || resultLabels.length != n) resultLabels = new String[n];
        if (resultMetas == null || resultMetas.length != n) resultMetas = new String[n];
        // The grid in full once the focus is down among the results; dimmer while it is on the field or the mic.
        int gridLayer = c.saveLayerAlpha(0, (S_GRID_TOP - 40) * k, w, h, Math.round(255 * Ui.clamp(resultsLit.value)));
        float gridWidth = S_COLS * S_W + (S_COLS - 1) * S_GAP, left = (1920 - gridWidth) / 2 * k, r0 = S_RADIUS * k;
        for (int i = 0; i < n; i++) {
            Recs.Pick p = results.get(i);
            int row = i / S_COLS, col = i % S_COLS;
            float x = left + col * (S_W + S_GAP) * k;
            float y = S_GRID_TOP * k + row * S_ROW * k - shift;
            if (y > h || y + S_ROW * k < 0) continue;
            gridCell.set(x, y, x + S_W * k, y + S_H * k);
            float rf = i < resultFocus.length ? Ui.clamp(resultFocus[i].value) : 0, rs = 1 + 0.07f * rf;
            Recs.Thumb t = recs.thumb(p);
            // The chosen card, as on the home screen: it grows and rises on a deeper shadow, with a thin bright rim
            // (nothing white laid over the picture).
            float cx = gridCell.centerX(), cy = gridCell.centerY() - 6 * k * rf;
            cell.set(cx - gridCell.width() * rs / 2, cy - gridCell.height() * rs / 2, cx + gridCell.width() * rs / 2, cy + gridCell.height() * rs / 2);
            float cr = r0 * rs;
            tmp.set(cell);
            tmp.offset(0, (10 + 14 * rf) * k);
            shadowDeep.setColor(Ui.alpha(0xFF000000, 0.35f + 0.3f * rf));
            shadows.draw(c, tmp, cr, DEEP_BLUR * k, shadowDeep);
            if (t != null) {
                roundImageCover(c, t.bitmap, t.shader, cell, cr);
            } else {
                fill.setShader(null);
                fill.setColor(0xFF2C2C2E);
                c.drawRoundRect(cell, cr, cr, fill);
                if (p.title != null) {   // no picture: the title on the card
                    fitPaint.set(gridLabel);
                    fitPaint.setColor(0xCCFFFFFF);
                    c.drawText(TextUtils.ellipsize(p.title, fitPaint, cell.width() - 40 * k, TextUtils.TruncateAt.END).toString(), cell.centerX(), cell.centerY() + Ui.halfCap(fitPaint), fitPaint);
                }
            }
            border.setColor(0x14FFFFFF);
            c.drawRoundRect(cell, cr, cr, border);
            drawFocusRing(c, cell, cr, rf);
            if (t != null) drawBadges(c, p, rs, 0);
            if (t != null && Feedback.UP.equals(p.verdict)) drawVerdict(c, rs, p.upcoming());
            if (resultLabels[i] == null) resultLabels[i] = TextUtils.ellipsize(p.title == null ? "" : p.title, sTitle, S_W * k, TextUtils.TruncateAt.END).toString();
            if (resultMetas[i] == null) resultMetas[i] = p.meta == null ? "" : TextUtils.ellipsize(p.meta, sMeta, S_W * k, TextUtils.TruncateAt.END).toString();
            sTitle.setColor(blend(0xD9FFFFFF, 0xFFFFFFFF, rf));
            c.drawText(resultLabels[i], gridCell.left, gridCell.bottom + 44 * k, sTitle);
            if (!resultMetas[i].isEmpty()) {
                sMeta.setColor(blend(0x99FFFFFF, 0xE6FFFFFF, rf));
                c.drawText(resultMetas[i], gridCell.left, gridCell.bottom + 78 * k, sMeta);
            }
        }
        c.restoreToCount(gridLayer);
    }

    // MARK: - TalkBack: every reachable item as a virtual node (Spoken). Id = group * 1000 + index + 1.

    /** The drawer's weather (0) and quotes (1) cards: read by TalkBack, never focused by the remote. */
    private static final int GRID = 9, WIDGETS = 8;
    /** The Search page: 0 the field, 1 the mic, 2 + i the results. The info page: its row of buttons. */
    private static final int SEARCH = 7, INFO_ROW = 6;

    private static int a11yId(int group, int index) {
        return group * 1000 + index + 1;
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private void a11yItems(List<Integer> out) {
        if (infoOpen) {
            for (int i = 0; i < infoActions.size(); i++) out.add(a11yId(INFO_ROW, i));
            return;
        }
        if (searchOpen) {
            out.add(a11yId(SEARCH, 0));
            out.add(a11yId(SEARCH, 1));
            if (canClear()) out.add(a11yId(SEARCH, 2));
            for (int i = 0; i < results.size(); i++) out.add(a11yId(SEARCH, 3 + i));
            return;
        }
        if (sheetOpen) {
            for (int i = 0; i < others.size(); i++) out.add(a11yId(GRID, i));
            return;
        }
        if (picksOn()) out.add(a11yId(Z_TOP, SEARCH_SLOT));
        if (picksOn()) for (int i = 0; i < nTabs(); i++) out.add(a11yId(Z_TOP, i));
        for (int i = TABS; i < TABS + lightButtons.size(); i++) out.add(a11yId(Z_TOP, i));
        for (int i = 0; i < visible(); i++) out.add(a11yId(Z_CARDS, i));
        for (int i = shelfFirst; i < Math.min(shelf.size(), shelfFirst + shelfVisible); i++) out.add(a11yId(Z_SHELF, i));
        for (int i = 0; i < watchItems.size(); i++) out.add(a11yId(Z_WATCH, i));
        for (int i = 0; i < drawerItems.size(); i++) out.add(a11yId(Z_DRAWER, i));
        if (drawerOpen && Prefs.weather()) out.add(a11yId(WIDGETS, 0));
        if (drawerOpen && Prefs.quotes() && HomeData.active().length > 0) out.add(a11yId(WIDGETS, 1));
    }

    private String a11yText(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case GRID:
                if (i >= others.size()) return "";
                return label(others, i) + ", " + Ui.trf("{i} of {n}", "i", i + 1, "n", others.size());
            case Z_TOP:
                if (i == SEARCH_SLOT) return Ui.tr("Search");
                if (i >= TABS) return label(lightButtons, i - TABS);
                if (i >= nTabs()) return "";
                return Ui.trf("{name}, tab {i} of {n}", "name", tabs()[i], "i", i + 1, "n", nTabs());
            case Z_CARDS: return i < visible() ? pickText(cards.get(cardAt(i))) : "";
            case INFO_ROW: return i < infoActions.size() ? infoLabel(i) : "";
            case SEARCH:
                if (i == 0) return field.getText().length() == 0 ? Ui.tr("Search for a film or a show") : field.getText().toString();
                if (i == 1) return Ui.tr("Search by voice");
                if (i == 2) return Ui.tr("Clear the search");
                if (i - 3 >= results.size()) return "";
                Recs.Pick p = results.get(i - 3);
                return pickText(p) + (p.detailed && p.pkg == null ? ". " + Ui.tr("Not on your streaming apps.") : "");
            case Z_SHELF:
                if (reordering && i == shelfIndex) {
                    return Ui.trf("Moving {app}, position {n} of {total}",
                            "app", label(shelf, i), "n", i + 1, "total", shelf.size());
                }
                return label(shelf, i);
            case Z_DRAWER: return label(drawerItems, i);
            case Z_WATCH: {
                if (i >= watchItems.size()) return "";
                Watching.Entry e = watchItems.get(i);
                return Ui.tr("Continue Watching") + ". " + e.line1 + (e.line2 == null || e.line2.isEmpty() ? "" : ", " + e.line2);
            }
            case WIDGETS: return i == 0 ? weatherText() : quotesText();
            default: return "";
        }
    }

    /**
     * Where the item is now. Items off screen (the closed drawer, the sheet's hidden rows, the top while the drawer is
     * open) get a thin strip at that edge, so TalkBack's arrows can still reach them; going there brings them in.
     */
    private boolean a11yBounds(int id, Rect out) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        float dy;
        switch (group) {
            case GRID: {
                if (!sheetOpen || i >= others.size()) return false;
                float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
                float x = ((1920 - gridWidth) / 2 + (i % COLUMNS) * (GRID_W + GRID_GAP)) * k;
                float y = (GRID_TOP + (i / COLUMNS - scroll.target) * GRID_ROW) * k;
                a11yRect.set(x, y, x + GRID_W * k, y + GRID_H * k);
                dy = 0;
                break;
            }
            case Z_TOP:
                if (i != SEARCH_SLOT && (i >= TABS + lightButtons.size() || (i >= nTabs() && i < TABS))) return false;
                a11yRect.set(topRects[i]);
                dy = (i < TABS || i == SEARCH_SLOT ? heroLayer : status).getTranslationY();
                break;
            case INFO_ROW:
                if (!infoOpen || i >= infoActions.size()) return false;
                a11yRect.set(infoRects[i]);
                dy = 0;
                break;
            case SEARCH:
                if (!searchOpen) return false;
                if (i == 0) a11yRect.set(fieldRect);
                else if (i == 1) a11yRect.set(micRect);
                else if (i == 2) a11yRect.set(clearRect);
                else if (i - 3 < results.size()) a11yRect.set(resultRect(i - 3, resultCell));
                else return false;
                dy = 0;
                break;
            case Z_CARDS:
                if (i >= visible()) return false;
                a11yRect.set(cardRects[i]);
                dy = heroLayer.getTranslationY();
                break;
            case Z_SHELF:
                if (i >= tileRects.length) return false;
                a11yRect.set(tileRects[i]);
                dy = tiles.getTranslationY();
                break;
            case Z_DRAWER:
                if (i >= drawerRects.length) return false;
                a11yRect.set(drawerRects[i]);
                dy = drawerOpen ? 0 : h;
                break;
            case Z_WATCH:
                if (i >= watchRects.length) return false;
                a11yRect.set(watchRects[i]);
                a11yRect.offset(watchScroll.value, 0);
                dy = watchLayer.getTranslationY();
                break;
            case WIDGETS:
                if (!drawerOpen) return false;
                a11yRect.set(i == 0 ? weatherCard : quotesCard);
                dy = 0;
                break;
            default:
                return false;
        }
        a11yRect.offset(0, dy);
        float edge = 24 * k;
        if (a11yRect.top >= h - edge) a11yRect.set(a11yRect.left, h - edge, a11yRect.right, h);
        else if (a11yRect.bottom <= edge) a11yRect.set(a11yRect.left, 0, a11yRect.right, edge);
        a11yRect.round(out);
        return out.width() > 0 && out.height() > 0;
    }

    private int a11yFocused() {
        if (infoOpen) return infoActions.isEmpty() ? -1 : a11yId(INFO_ROW, infoIndex);
        if (searchOpen) return a11yId(SEARCH, sZone == S_RESULTS ? 3 + sIndex : sZone == S_CLEAR ? 2 : sZone);
        if (sheetOpen) return others.isEmpty() ? -1 : a11yId(GRID, gridIndex);
        switch (zone) {
            case Z_TOP: return a11yId(Z_TOP, topIndex);
            case Z_CARDS: return cardIndex < cards.size() ? a11yId(Z_CARDS, slotOf(cardIndex)) : -1;
            case Z_SHELF: return shelf.isEmpty() ? -1 : a11yId(Z_SHELF, shelfIndex);
            case Z_DRAWER: return drawerItems.isEmpty() ? -1 : a11yId(Z_DRAWER, drawerIndex);
            case Z_WATCH: return watchItems.isEmpty() ? -1 : a11yId(Z_WATCH, watchIndex);
            case WIDGETS: return a11yId(WIDGETS, widgetIndex);
            default: return -1;
        }
    }

    /** The drawer's widgets that are on (0 weather, 1 quotes), with the drawer open. */
    private List<Integer> widgets() {
        List<Integer> out = new ArrayList<>();
        if (!drawerOpen) return out;
        if (Prefs.weather()) out.add(0);
        if (Prefs.quotes() && HomeData.active().length > 0) out.add(1);
        return out;
    }

    /** A spoken-feedback service (TalkBack) is on. */
    private boolean talkBack() {
        AccessibilityManager am = (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        return am != null && am.isEnabled()
                && !am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
    }

    /** TalkBack moved to an item: the remote's focus goes there (opening or closing the drawer on the way). */
    private void a11yFocus(int id) {
        wake();
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        if (group == INFO_ROW) {
            if (!infoOpen || i >= infoActions.size()) return;
            infoIndex = i;
            aim(infoFocus, infoIndex, false);
            click.move();
            return;
        }
        if (infoOpen) return;
        if (group == SEARCH) {
            if (!searchOpen || (i >= 3 && i - 3 >= results.size())) return;
            sZone = i >= 3 ? S_RESULTS : i == 2 ? S_CLEAR : i;
            if (i >= 3) sIndex = i - 3;
            retargetSearch(false);
            click.move();
            return;
        }
        if (group == GRID) {
            if (!sheetOpen || i >= others.size()) return;
            gridIndex = i;
            retargetGrid(false);
            gridDirty = true;
            click.move();
            return;
        }
        if (covered() || reordering) return;
        switch (group) {
            case Z_DRAWER:
                if (i >= drawerItems.size()) return;
                if (!drawerOpen) openDrawer();
                drawerIndex = i;
                break;
            case WIDGETS:
                if (!widgets().contains(i)) return;
                widgetIndex = i;
                break;
            case Z_TOP:
                if (i == SEARCH_SLOT ? !picksOn() : i >= TABS + lightButtons.size() || (i < TABS && (!picksOn() || i >= nTabs()))) return;
                if (drawerOpen) closeDrawer();
                topIndex = i;
                if (i < TABS) selectTab(i);
                break;
            case Z_CARDS:
                if (i >= visible()) return;
                if (drawerOpen) closeDrawer();
                cardIndex = cardAt(i);
                break;
            case Z_SHELF:
                if (i >= shelf.size()) return;
                if (drawerOpen) closeDrawer();
                if (peekOpen) closeWatch();
                shelfIndex = i;
                break;
            case Z_WATCH:
                if (i >= watchItems.size()) return;
                if (drawerOpen) closeDrawer();
                if (!peekOpen) openWatch();
                watchIndex = i;
                break;
            default:
                return;
        }
        zone = group;
        retarget(false);
        click.move();
    }

    private static String label(List<HomeApps.Item> list, int i) {
        return i >= 0 && i < list.size() && list.get(i).label != null ? list.get(i).label : "";
    }

    /** A recommendation: title, details and the app that plays it. */
    private static String pickText(Recs.Pick p) {
        if (p == null) return "";
        StringBuilder s = new StringBuilder(p.title == null ? "" : p.title);
        if (p.meta != null && !p.meta.isEmpty()) s.append(", ").append(p.meta);
        String app = Recs.serviceName(p);
        if (app != null && !app.isEmpty()) s.append(". App: ").append(app);
        return s.toString();
    }

    /** The weather card as TalkBack reads it: what it shows, in the same words and units. */
    private String weatherText() {
        StringBuilder s = new StringBuilder(Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city());
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp)) return s.append(". ").append(Ui.tr("No weather data right now.")).toString();
        s.append(". ").append(HomeData.degrees(wx.temp)).append(", ").append(HomeData.condition(wx.code, wx.isDay)).append(". ")
                .append(Ui.trf("High {max}, low {min}.", "max", HomeData.degrees(wx.max), "min", HomeData.degrees(wx.min)));
        int hours = Math.min(6, wx.hours.size());
        if (hours > 0) {
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < hours; i++) {
                if (i > 0) list.append(", ");
                list.append(HomeData.hour(wx.hours.get(i))).append(' ').append(HomeData.degrees(wx.hourTemp.get(i)));
            }
            s.append(' ').append(Ui.trf("Next hours: {list}.", "list", list));
        }
        if (wx.sunrise != 0) s.append(' ').append(Ui.trf("talkback|Sunrise {time}", "time", Ui.time(wx.sunrise))).append('.');
        if (wx.sunset != 0) s.append(' ').append(Ui.trf("Sunset {time}", "time", Ui.time(wx.sunset))).append('.');
        return s.toString();
    }

    /** The quotes card as TalkBack reads it: each coin with its price and 24 h change. */
    private String quotesText() {
        StringBuilder s = new StringBuilder(Ui.tr("Crypto"));
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        s.append(", ").append(stale.isEmpty() ? Ui.tr("24h change") : Ui.trf("quotes|{ago}", "ago", stale)).append('.');
        for (int i : HomeData.active()) {
            s.append(' ').append(HomeData.coinName(i)).append(' ').append(HomeData.brl(data.prices[i]));
            if (!Double.isNaN(data.changes[i])) s.append(", ").append(HomeData.change(data.changes[i]));
            s.append('.');
        }
        return s.toString();
    }

    // MARK: - Light design: title, tabs, cards and icon-only buttons

    private void layoutLight() {
        if (lTab == null) return;
        // Text tabs; the focus pill extends 26 px past the text (the first tab's text starts at the margin).
        float pad = 26 * k, tabTop = L_TABBAR_TOP * k, tabBottom = (L_TABBAR_TOP + L_TABBAR_H) * k;
        // Search first, a tab like the others with the magnifier before its name; then the tabs.
        float icon = 26 * k, iw = Symbols.width(getContext(), "search", icon);
        float sw = iw + 12 * k + Math.max(lTabOn.measureText(Ui.tr("Search")), lTab.measureText(Ui.tr("Search")));
        topRects[SEARCH_SLOT].set(MARGIN * k - pad, tabTop, MARGIN * k - pad + sw + 2 * pad, tabBottom);
        snap(topRects[SEARCH_SLOT]);
        float x = topRects[SEARCH_SLOT].right + 22 * k;   // the first tab's pill grows 5 % when focused: it must clear the capsule
        for (int i = 0; i < nTabs(); i++) {
            float tw = Math.max(lTabOn.measureText(tabs()[i]), lTab.measureText(tabs()[i]));
            topRects[i].set(x, tabTop, x + tw + 2 * pad, tabBottom);
            snap(topRects[i]);
            x = topRects[i].right + 4 * k;
        }
        float b = L_BUTTON * k, right = CONTENT_R * k, cy = L_BUTTON_CY * k;
        for (int i = lightButtons.size() - 1; i >= 0; i--) {
            topRects[TABS + i].set(right - b, cy - b / 2, right, cy + b / 2);
            right -= b + L_BUTTON_GAP * k;
        }
        layoutCards();
        for (HomeApps.Item it : lightButtons) {
            Symbols.warm(getContext(), it.symbol, 27 * k);
            Symbols.warm(getContext(), it.symbol, 27 * k * 1.1f);
        }
        Symbols.warm(getContext(), "layers", 18 * k);
        Symbols.warm(getContext(), "search", 26 * k);
        Symbols.warm(getContext(), "search", 27 * k);
        Symbols.warm(getContext(), "mic", 27 * k);
        Symbols.warm(getContext(), "mic", 27 * k * 1.1f);
        Symbols.warm(getContext(), "close", 25 * k);
        Symbols.warm(getContext(), "close", 25 * k * 1.1f);
        Symbols.warmWeather(getContext(), 40 * k);
        layoutSearch();
        heroDirty = statusDirty = true;
    }

    /**
     * The cards as an accordion: one wide, the others tall, the wide one being the featured card (cardWide, animated).
     * The widths share the same row, so the row stays the same width however the springs stand.
     */
    private void layoutCards() {
        float top = L_CARDS_TOP * k, ch = L_CARD_H * k, g = L_CARD_GAP * k, left = MARGIN * k, tall = L_TALL_W * k;
        float wide = (CONTENT_R - MARGIN) * k - (VISIBLE - 1) * (tall + g);
        float total = 0;
        for (Spring s : cardWide) total += Math.max(0, s.value);
        float x = left;
        for (int i = 0; i < cardRects.length; i++) {
            float w = total > 0.001f ? Math.max(0, cardWide[i].value) / total : (i == 0 ? 1 : 0);
            float cw = tall + (wide - tall) * w;
            cardRects[i].set(x, top, x + cw, top + ch);
            snap(cardRects[i]);
            x += cw + g;
        }
    }

    /** Slots in use: VISIBLE, or fewer picks. */
    private int visible() {
        return Math.min(VISIBLE, cards.size());
    }

    /** The pick shown in a slot (the window starts at cardStart and wraps around the hour's picks). */
    private int cardAt(int slot) {
        return cards.isEmpty() ? 0 : (cardStart + slot) % cards.size();
    }

    /** The slot of a pick that is in the window. */
    private int slotOf(int card) {
        int n = cards.size();
        return n == 0 ? 0 : ((card - cardStart) % n + n) % n;
    }

    /** The background follows the featured card (picks on; otherwise updatePicks decides). */
    private void showFeaturedBackdrop() {
        if (!picksOn() || cards.isEmpty()) return;
        Backdrop b = recs.backdrop(cards.get(cardAt(Math.min(featured, visible() - 1))));
        if (b != null) showBackdrop(b);
    }

    /** The three cards of the current tab (Explore, Movies, Shows) and the background taken from the featured one. */
    private void updateCards() {
        Tabs.Tab current = tabList.get(Math.min(tab, nTabs() - 1));
        recs.setCurrent(current);
        List<Recs.Pick> list = recs.now(current);
        if (!samePicks(list, cards)) {
            cards = list;
            cardStart = 0;
            featuredCard = -1;
            cardIndex = Math.min(cardIndex, Math.max(0, list.size() - 1));
            featured = Math.min(featured, Math.max(0, visible() - 1));
            if (zone != Z_CARDS) {   // new picks while elsewhere: the first one leads, as before
                cardIndex = featured = 0;
                aim(cardWide, 0, false);
            }
            if (zone == Z_CARDS && list.isEmpty()) {
                zone = Z_SHELF;
            }
            retarget(false);   // the wide slot and the focus follow (a rated card left: fewer slots)
        }
        for (Recs.Pick p : cards) p.verdict = Account.verdict(getContext(), Recs.key(p));
        for (Recs.Pick p : cards) recs.backdrop(p);   // this tab's cards first...
        for (Tabs.Tab t : tabList) if (t != current) for (Recs.Pick p : recs.now(t)) recs.backdrop(p);   // ...then the other tabs', so switching shows them at once
        // Picks bring their own background; without them (no TMDB key), your photo if there is one.
        boolean own = !picksOn() && Photos.on(getContext());
        Backdrop b = own ? Photos.backdrop(getContext(), ownReady)
                : cards.isEmpty() ? null : recs.backdrop(cards.get(cardAt(Math.min(featured, visible() - 1))));
        if (b != null || (cards.isEmpty() && !own)) showBackdrop(b);
        heroDirty = true;
        spoken.focusMoved();    // new picks under the focused card
    }

    /** Your photo's background is ready (or new photos came in): draw it. */
    private final Runnable ownReady = () -> {
        if (running) updatePicks();
    };

    private void selectTab(int index) {
        if (index == tab || index >= nTabs()) return;
        tab = index;
        layoutLight();   // the selected tab's name may carry its window or language
        retarget(true);
        updateCards();
        heroDirty = true;
    }

    /** "Other picks": the open tab's next set of cards, now (OK on the tab's name, or in a card's menu). */
    private void otherPicks() {
        if (!picksOn() || nTabs() == 0) return;
        recs.turn(tabList.get(Math.min(tab, nTabs() - 1)));
        click.move();
        updateCards();
    }

    /**
     * Background: the first card's photo, heavily blurred and almost white; without it, a light gradient. At night, the
     * same photo almost black (or a dark gradient), drawn on top during the transition.
     */
    private void drawLightSky(Canvas c) {
        long up = SystemClock.uptimeMillis();
        float e = bgFadeAt == 0 ? 1 : fadeEase(bgFadeAt, up);
        boolean fading = bgFadeAt != 0;
        // Drawer open: the background lightens (by day) or darkens (at night), as a filter on the drawing itself.
        android.graphics.ColorFilter v = veil(tone(0.32f, 0.42f) * Math.max(Ui.clamp(drawer.value), 0.45f * Ui.clamp(peek.value)), blend(0xFFFFFFFF, 0xFF000000, night));
        if (bgTo == null || (fading && bgFrom == null)) {
            if (lightGradient == null) lightGradient = Ui.gradient(h, 0xFFF5F5F7, 0xFFE2E2E7);
            if (nightGradient == null) nightGradient = Ui.gradient(h, 0xFF1C1C1E, 0xFF0B0B0D);
            fill.setColorFilter(v);
            fill.setColor(0xFF000000);
            if (night < 0.999f) {
                fill.setShader(lightGradient);
                c.drawRect(screen, fill);
            }
            if (night > 0.001f) {
                fill.setShader(nightGradient);
                fill.setAlpha(Math.round(255 * night));
                c.drawRect(screen, fill);
                fill.setAlpha(255);
            }
            fill.setShader(null);
            fill.setColorFilter(null);
        }
        imagePaint.setColorFilter(v);
        if (fading && bgFrom != null) drawLightImage(c, bgFrom, bgTo == null ? 1 - e : 1);
        if (bgTo != null) drawLightImage(c, bgTo, fading ? e : 1);
        imagePaint.setColorFilter(null);
    }

    private void drawLightImage(Canvas c, Backdrop b, float alpha) {
        if (night < 0.999f || b.nightImage() == null) drawImage(c, b.lightImage(), alpha);
        if (night > 0.001f) drawImage(c, b.nightImage(), alpha * night);
    }

    private void drawLightTop(Canvas c) {
        applyTone();
        if (!picksOn()) {
            drawClock(c);
            if (HomeData.coinCount() > 0) drawQuotes(c);
            return;
        }
        String hello = Config.TITLE.trim().isEmpty() ? Ui.tr("For You") : Config.TITLE.trim();
        c.drawText(hello, MARGIN * k - Ui.leftBearing(lTitle, hello), L_TITLE_BASE * k, lTitle);
        drawSearchTab(c);
        // Text tabs (the selected one in bold, in the main text color); with focus on the tabs, the selected one gets
        // the white pill with a shadow and grows a little (the tvOS focus).
        float focus = Ui.clamp(topFocus[tab].value);
        for (int i = 0; i < nTabs(); i++) {
            RectF r = topRects[i];
            if (i == tab && focus > 0.01f) {
                float s = 1 + 0.05f * focus;
                cell.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2,
                        r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2);
                float rr = cell.height() / 2;
                if (focus > 0.01f) {
                    tmp.set(cell);
                    tmp.offset(0, 8 * k * focus);
                    shadow.setColor(Ui.alpha(0xFF000000, tone(0.22f, 0.4f) * focus));
                    shadows.draw(c, tmp, rr, SHADOW_BLUR * k, shadow);
                }
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, focus));
                c.drawRoundRect(cell, rr, rr, fill);
            }
            Paint p = i == tab ? lTabOn : lTab;
            if (i == tab) lTabOn.setColor(blend(tone(L_INK, WHITE), L_INK, focus));   // always dark on the white pill
            c.drawText(tabs()[i], r.centerX() - p.measureText(tabs()[i]) / 2, r.centerY() + Ui.halfCap(p), p);
        }
        int top = -1;
        for (int i = 0; i < VISIBLE; i++) if (cardFocus[i].value > 0.02f && (top < 0 || cardFocus[i].value > cardFocus[top].value)) top = i;
        drawLeavingCard(c);
        for (int i = 0; i < VISIBLE; i++) if (i != top) drawLightCard(c, i);
        if (top >= 0) drawLightCard(c, top);
        if (HomeData.coinCount() > 0) drawQuotes(c);
    }

    private boolean picksOn() {
        return Recs.hasKey(getContext());
    }

    /**
     * The Search tab: a glass capsule (like the round buttons at the top right) with the magnifier and its name, so it
     * reads as a tool, not one more list; focused, it turns white like a focused tab.
     */
    private void drawSearchTab(Canvas c) {
        RectF r = topRects[SEARCH_SLOT];
        float f = Ui.clamp(topFocus[SEARCH_SLOT].value), s = 1 + 0.05f * f;
        cell.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2 + 6 * k, r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2 - 6 * k);
        float rr = cell.height() / 2;
        tmp.set(cell);
        tmp.offset(0, (4 + 6 * f) * k);
        shadow.setColor(Ui.alpha(0xFF000000, tone(0.1f, 0.3f) + tone(0.14f, 0.2f) * f));
        shadows.draw(c, tmp, rr, SHADOW_BLUR * k, shadow);
        scene.liquid(c, cell, rr, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 12 * k, 8 * k, tone(0.6f, 0.45f), 1 - f);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, f));
            c.drawRoundRect(cell, rr, rr, fill);
        }
        Paint p = lTabOn;
        int ink = blend(tone(L_INK, 0xEBFFFFFF), L_INK, f);
        float icon = 26 * k, iw = Symbols.width(getContext(), "search", icon), pad = 26 * k;
        float x = r.left + pad;
        Symbols.draw(getContext(), c, "search", x + iw / 2, r.centerY(), icon, ink);
        int was = p.getColor();
        p.setColor(ink);
        c.drawText(Ui.tr("Search"), x + iw + 12 * k, r.centerY() + Ui.halfCap(p), p);
        p.setColor(was);
    }

    private void drawClock(Canvas c) {
        float x = MARGIN * k;
        c.drawText(dateText, x - Ui.leftBearing(lDate, dateText), L_DATE_BASE * k, lDate);
        c.drawText(clockText, x - Ui.leftBearing(lClock, clockText), L_CLOCK_BASE * k, lClock);
        HomeData.Weather wx = data.weather;
        if (!Prefs.weather() || Double.isNaN(wx.temp)) return;
        if (data.weatherAt != weatherLineAt || Prefs.version != weatherLinePrefs || !Ui.language.equals(weatherLineLanguage)) {
            weatherLineAt = data.weatherAt;
            weatherLinePrefs = Prefs.version;
            weatherLineLanguage = Ui.language;
            weatherLine = HomeData.degrees(wx.temp) + "  " + HomeData.condition(wx.code, wx.isDay) + "   "
                    + HomeData.highLow(wx, "  ");
        }
        float size = 40 * k, base = L_WEATHER_BASE * k;
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(lWeather), size, lWeather.getColor());
        c.drawText(weatherLine, x + sw + 16 * k, base, lWeather);
    }

    /** A slot's card: while the row slides, at its old place moving to the new one (the one coming in fades in). */
    private void drawLightCard(Canvas c, int i) {
        Recs.Pick p = i < visible() ? cards.get(cardAt(i)) : null;
        if (p == null) {
            // Nothing yet while the titles are being fetched (a cold start): the row's shape, in gray, and a word under
            // it, instead of a blank screen. Fewer picks than slots (a thin tab) draws no empty card.
            if (cards.isEmpty() && picksOn() && recs.fetching()) {
                drawLightCard(c, null, cardRects[i], 0, cardWide[i].value, 1);
                if (i == 0) c.drawText(Ui.tr("Fetching titles…"), cardRects[0].left, cardRects[0].bottom + 46 * k, lCardTitle);
            }
            return;
        }
        float t = slide.value;
        RectF base = cardRects[i];
        float alpha = 1;
        if (Math.abs(t) > 0.001f) {
            slotRect(i + t, slideRect);
            base = slideRect;
            if (i == (t > 0 ? visible() - 1 : 0)) alpha = 1 - Math.min(1, Math.abs(t));
        }
        drawLightCard(c, p, base, cardFocus[i].value, cardWide[i].value, alpha);
    }

    /** While the row slides: the pick that leaves, on its way out past the end of the row, fading. */
    private void drawLeavingCard(Canvas c) {
        float t = slide.value;
        if (Math.abs(t) < 0.001f || slidePick < 0 || slidePick >= cards.size()) return;
        slotRect((t > 0 ? -1 : visible()) + t, slideRect);
        drawLightCard(c, cards.get(slidePick), slideRect, 0, 0, Math.min(1, Math.abs(t)));
    }

    /** The window moved on by `dir` slots: the row starts that far from where it will rest. */
    private void startSlide(int dir) {
        slide.snap(Math.max(-2, Math.min(2, slide.value + dir)));
        slide.target = 0;
        heroDirty = true;
    }

    /** The card rectangle at a fractional slot: between two slots' rectangles; past the ends, a tall card further. */
    private void slotRect(float pos, RectF out) {
        int a = (int) Math.floor(pos);
        float f = pos - a;
        virtualSlot(a, slideA);
        virtualSlot(a + 1, slideB);
        out.set(slideA.left + (slideB.left - slideA.left) * f, slideA.top, slideA.right + (slideB.right - slideA.right) * f, slideA.bottom);
    }

    private void virtualSlot(int slot, RectF out) {
        float tall = L_TALL_W * k, g = L_CARD_GAP * k;
        if (slot < 0) {
            float right = cardRects[0].left - g + (slot + 1) * (tall + g);
            out.set(right - tall, cardRects[0].top, right, cardRects[0].bottom);
        } else if (slot >= VISIBLE) {
            float left = cardRects[VISIBLE - 1].right + g + (slot - VISIBLE) * (tall + g);
            out.set(left, cardRects[0].top, left + tall, cardRects[0].bottom);
        } else {
            out.set(cardRects[slot]);
        }
    }

    /**
     * Recommendation card: the title's photo (already at the focused card size, so it stays sharp), rounded corners and
     * a soft shadow; when focused (v → 1), it grows 5%, rises and lights up its border. Below it, the title (and on the
     * large card, genre · runtime · service), which fade out while the card's menu is open. `alpha` < 1 draws it all
     * through a layer (only while the row slides).
     */
    private void drawLightCard(Canvas c, Recs.Pick p, RectF base, float v, float wideness, float alpha) {
        boolean isWide = wideness > 0.5f;
        int layer = -1;
        if (alpha < 0.999f) {
            if (alpha <= 0.001f) return;
            layer = c.saveLayerAlpha(base.left - 60 * k, base.top - 60 * k, base.right + 60 * k, base.bottom + 150 * k, Math.round(255 * alpha));
        }
        float f = Ui.clamp(v), s = 1 + 0.05f * v;
        float bw = base.width(), bh = base.height(), r0 = 18 * k;
        float cx = base.centerX(), cy = base.centerY() - 6 * k * v;
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (10 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * f));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        Backdrop b = p == null ? null : recs.backdrop(p);
        Bitmap img = b == null ? null : (isWide ? b.wide : b.tall);
        if (img != null) {
            roundImageCover(c, img, isWide ? b.wideShader : b.tallShader, cell, r);
        } else {
            fill.setShader(null);
            fill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
            c.drawRoundRect(cell, r, r, fill);
        }
        if (f > 0.01f) {
            if (lightSheen == null) {   // once, in the unit square; shaderMatrix puts them on each card (no allocation per frame)
                lightSheen = new LinearGradient(0, 0, 0.5f, 1, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                lightRim = new LinearGradient(0, 0, 0, 1, 0xE6FFFFFF, 0x59FFFFFF, Shader.TileMode.CLAMP);
            }
            shaderMatrix.setScale(bw, bh);
            shaderMatrix.postTranslate(-bw / 2, -bh / 2);
            lightSheen.setLocalMatrix(shaderMatrix);
            lightRim.setLocalMatrix(shaderMatrix);
            int alphaRim = Math.round(255 * f);
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            // No sheen over the picture (the white wash read as a pale disc on dark pictures): the rim alone says "focused".
            rim.setShader(lightRim);
            rim.setAlpha(alphaRim);
            rim.setStrokeWidth(2.5f * k / s);
            c.drawRoundRect(tmp, r0, r0, rim);
            rim.setStrokeWidth(2.5f * k);
            rim.setAlpha(255);
            c.restore();
        }
        if (p != null) {
            if (img != null) drawBadges(c, p, s, Ui.clamp((wideness - 0.6f) / 0.4f));
            if (img != null && Feedback.UP.equals(p.verdict)) drawVerdict(c, s, p.upcoming());
            // The text under the card fades while its menu is open (the row of buttons takes its place).
            float textA = menuPick == p && menu.value > 0.001f ? 1 - Ui.clamp(menu.value) : 1;
            int titleA = lCardTitle.getAlpha(), metaA = lCardMeta.getAlpha();
            lCardTitle.setAlpha(Math.round(titleA * textA));
            fitPaint.set(lCardTitle);
            c.drawText(TextUtils.ellipsize(p.title, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 46 * k, lCardTitle);
            lCardTitle.setAlpha(titleA);
            // Genre · runtime · service: always under the large card, and under a small one while it is focused.
            if (p.meta != null && !p.meta.isEmpty() && (isWide || f > 0.01f)) {
                fitPaint.set(lCardMeta);
                lCardMeta.setAlpha(Math.round(metaA * textA * (isWide ? 1 : f)));
                c.drawText(TextUtils.ellipsize(p.meta, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 80 * k, lCardMeta);
            }
            // Why this pick (the recommendation profile's reason), under the wide card only.
            if (isWide && p.reason != null && !p.reason.isEmpty()) {
                fitPaint.set(lCardMeta);
                lCardMeta.setAlpha(Math.round(metaA * textA));
                c.drawText(TextUtils.ellipsize(p.reason, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 112 * k, lCardMeta);
            }
            lCardMeta.setAlpha(metaA);
        }
        if (layer >= 0) c.restoreToCount(layer);
    }

    /** On your watchlist: a bookmark top left on the card; on a title not out yet, the bell of your reminder. */
    private void drawVerdict(Canvas c, float s, boolean reminder) {
        float h = 34 * k * s, m = 14 * k * s;
        tmp.set(cell.left + m, cell.top + m, cell.left + m + h, cell.top + m + h);
        c.drawRoundRect(tmp, h / 2, h / 2, scorePill);
        Symbols.draw(getContext(), c, reminder ? "bell" : "bookmark", tmp.centerX(), tmp.centerY(), 17 * k * s, GIVEN);
    }

    /** "2" to "9": how many of the TV's apps have the title (text made once: nothing is built while drawing). */
    private static final String[] APP_COUNTS = {"", "", "2", "3", "4", "5", "6", "7", "8", "9"};

    /**
     * The badges top right on the card (`cell`), on dark pills, growing with the card (`s`), right to left: TMDB's user
     * score ("★ 7.8"), a stack with the number of your apps that have the title (its page asks which) and, as the
     * card grows wide (`full`, 0 to 1: they fade in with it, so nothing pops while the accordion moves), the ratings
     * as JustWatch shows them: IMDb, Rotten Tomatoes, JustWatch.
     */
    private void drawBadges(Canvas c, Recs.Pick p, float s, float full) {
        // One size whatever the card's scale: text that grew with the card re-measured on every frame and jittered.
        float h = 34 * k, pad = 12 * k, m = 14 * k, right = cell.right - m;
        lScore.setTextSize(20 * k);
        right = pill(c, p.scoreText, right, h, pad);
        if (!p.alts.isEmpty()) {
            String n = APP_COUNTS[Math.min(APP_COUNTS.length - 1, p.alts.size() + 1)];
            float icon = 18 * k, iw = Symbols.width(getContext(), "layers", icon);
            float w = iw + 6 * k + lScore.measureText(n) + 2 * pad;
            tmp.set(right - w, cell.top + m, right, cell.top + m + h);
            c.drawRoundRect(tmp, h / 2, h / 2, scorePill);
            Symbols.draw(getContext(), c, "layers", tmp.left + pad + iw / 2, tmp.centerY(), icon, 0xFFFFFFFF);
            c.drawText(n, tmp.left + pad + iw + 6 * k, tmp.centerY() + Ui.halfCap(lScore), lScore);
            right = tmp.left - 8 * k;
        }
        if (full > 0.01f) {
            int pillA = scorePill.getAlpha(), textA = lScore.getAlpha();
            scorePill.setAlpha(Math.round(pillA * full));
            lScore.setAlpha(Math.round(textA * full));
            right = pill(c, p.imdbText, right, h, pad);
            right = pill(c, p.tomatoText, right, h, pad);
            pill(c, p.jwText, right, h, pad);
            scorePill.setAlpha(pillA);
            lScore.setAlpha(textA);
        }
    }

    /** One dark pill ending at `right` with `text` (none when empty); returns where the next one ends. */
    private float pill(Canvas c, String text, float right, float h, float pad) {
        if (text.isEmpty()) return right;
        float w = lScore.measureText(text) + 2 * pad;
        tmp.set(right - w, cell.top + 14 * k, right, cell.top + 14 * k + h);
        c.drawRoundRect(tmp, h / 2, h / 2, scorePill);
        c.drawText(text, tmp.left + pad, tmp.centerY() + Ui.halfCap(lScore), lScore);
        return tmp.left - 8 * k;
    }

    /**
     * The round buttons (Live TV, HDMI Inputs and Settings by default): icon-only glass circles (light by day, smoked at
     * night); the focused one turns white with a dark icon (the tvOS focus) and its name appears below it.
     */
    private void drawLightButtons(Canvas c) {
        applyTone();
        for (int i = 0; i < lightButtons.size(); i++) {
            HomeApps.Item it = lightButtons.get(i);
            drawRoundButton(c, it.symbol, it.label, topRects[TABS + i], Ui.clamp(topFocus[TABS + i].value), 27 * k);
        }
    }

    /** One icon-only glass circle (`r`), focused by `f`, its name under it while focused. */
    /**
     * The focus ring: a crisp white stroke a few pixels outside the focused tile, as Google TV, Netflix and Fire TV draw
     * theirs, so where you are reads at a glance from the sofa (the growth, the shadow and the thin rim alone didn't:
     * "I had to move to find the focus"). A dark hairline outside the stroke keeps it visible over a light tile or the
     * platter's glass. No glow: a blurred fill laid over the tile fogged it. `f` is the focus (0 to 1); `r` the tile's
     * corner radius. Allocates nothing.
     */
    private void drawFocusRing(Canvas c, RectF tile, float r, float f) {
        if (f < 0.01f) return;
        float gap = 4 * k, width = 3.5f * k, out = gap + width / 2;
        tmp.set(tile.left - out, tile.top - out, tile.right + out, tile.bottom + out);
        float rr = r + out;
        rim.setShader(null);
        rim.setColor(Ui.alpha(0xFF000000, 0.35f * f));
        rim.setStrokeWidth(width + 2 * k);   // the hairline, on both sides of the stroke
        c.drawRoundRect(tmp, rr, rr, rim);
        rim.setColor(Ui.alpha(0xFFFFFFFF, 0.96f * f));
        rim.setStrokeWidth(width);
        c.drawRoundRect(tmp, rr, rr, rim);
        rim.setStrokeWidth(2.5f * k);
    }

    private void drawRoundButton(Canvas c, String symbol, String label, RectF r, float f, float symbolSize) {
        drawRoundButton(c, symbol, label, r, f, f, symbolSize);
    }

    /** `f` lights the button, `labelF` its label under it: a lit button next to a focused one shows no label (the two overlapped). */
    private void drawRoundButton(Canvas c, String symbol, String label, RectF r, float f, float labelF, float symbolSize) {
        float s = 1 + 0.1f * f, rad = r.width() / 2 * s;
        cell.set(r.centerX() - rad, r.centerY() - rad, r.centerX() + rad, r.centerY() + rad);
        tmp.set(cell);
        tmp.offset(0, (6 + 8 * f) * k);
        shadow.setColor(Ui.alpha(0xFF000000, tone(0.1f, 0.3f) + tone(0.14f, 0.2f) * f));
        shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
        scene.liquid(c, cell, rad, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 12 * k, 8 * k,
                tone(0.6f, 0.45f), 1 - f);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, f));
            c.drawCircle(cell.centerX(), cell.centerY(), rad, fill);
        }
        Symbols.draw(getContext(), c, symbol, cell.centerX(), cell.centerY(), symbolSize * s,
                blend(tone(L_INK, 0xEBFFFFFF), L_INK, f));
        if (labelF > 0.01f) {
            float half = lTip.measureText(label) / 2;
            float x = Math.max(MARGIN * k + half, Math.min(r.centerX(), CONTENT_R * k - half));
            lTip.setAlpha(Math.round(255 * labelF));
            c.drawText(label, x, r.bottom + (40 + 4 * f) * k, lTip);
        }
    }

    private boolean moveLight(int dx, int dy) {
        switch (zone) {
            case Z_SHELF:
                // Up from an empty shelf too: move()'s older zones (the hero, the corner capsule) aren't drawn any more.
                if (dy >= 0) return false;
                if (cards.isEmpty()) {
                    if (!picksOn() && lightButtons.isEmpty()) return true;   // no tabs and no buttons above
                    zone = Z_TOP;
                    topIndex = picksOn() ? tab : TABS;
                } else {
                    zone = Z_CARDS;
                    cardIndex = cardAt(nearest(cardRects, visible(), shelf.isEmpty() ? w / 2f : tileRects[shelfIndex].centerX()));
                }
                break;
            case Z_CARDS:
                if (dx != 0) {
                    int n = cards.size(), slot = slotOf(cardIndex), v = visible();
                    if (n <= VISIBLE) {   // all picks on screen: after the last comes the first, nothing slides
                        int next = wrap(cardIndex, dx, n);
                        if (next == cardIndex) return true;
                        cardIndex = next;
                    } else if (dx > 0) {   // past the last slot the window moves on (the row slides), and after the last pick comes the first
                        if (slot < v - 1) cardIndex = cardAt(slot + 1);
                        else { slidePick = cardAt(0); cardStart = (cardStart + 1) % n; cardIndex = cardAt(v - 1); startSlide(1); }
                    } else {
                        if (slot > 0) cardIndex = cardAt(slot - 1);
                        else { slidePick = cardAt(v - 1); cardStart = (cardStart - 1 + n) % n; cardIndex = cardAt(0); startSlide(-1); }
                    }
                    heroDirty = true;   // the slots may show other picks now
                } else if (dy < 0) {
                    zone = Z_TOP;
                    topIndex = tab;     // as on Apple TV: moving up to the tabs focuses the selected tab
                } else {
                    zone = Z_SHELF;
                    if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, tileRects.length, cardRects[slotOf(cardIndex)].centerX());
                }
                break;
            case Z_TOP:
                if (dx != 0) {
                    // The row is the magnifier, the tabs (0..nTabs-1), then the buttons (TABS..): the unused tab slots are skipped.
                    // The row wraps: after the last button comes the magnifier again, before the magnifier the last button.
                    int next = topRow(topIndex, dx);
                    if (next == topIndex) return true;
                    topIndex = next;
                    if (topIndex < TABS) selectTab(topIndex);   // like Apple TV tabs: focusing a tab selects it
                } else if (dy > 0) {
                    if (cards.isEmpty()) {
                        zone = Z_SHELF;
                    } else {
                        zone = Z_CARDS;
                        cardIndex = cardAt(nearest(cardRects, visible(), topRects[topIndex].centerX()));
                    }
                } else if ((topIndex < TABS || topIndex == SEARCH_SLOT) && !lightButtons.isEmpty()) {
                    topIndex = TABS;   // up from the tabs reaches the corner buttons without walking the row
                } else {
                    return true;
                }
                break;
            default:
                return false;
        }
        retarget(false);
        click.move();
        return true;
    }

    /** The slot `dx` steps along the top row from `at`: the magnifier, the tabs, then the corner buttons, wrapping at both ends. */
    private int topRow(int at, int dx) {
        int tabs = picksOn() ? 1 + nTabs() : 0, n = tabs + lightButtons.size();   // the magnifier counts with the tabs
        if (n == 0) return at;
        int pos;
        if (!picksOn()) pos = at != SEARCH_SLOT && at >= TABS ? at - TABS : 0;
        else pos = at == SEARCH_SLOT ? 0 : at < TABS ? at + 1 : tabs + at - TABS;
        pos = wrap(pos, dx, n);
        if (!picksOn()) return TABS + pos;
        return pos == 0 ? SEARCH_SLOT : pos < tabs ? pos - 1 : TABS + pos - tabs;
    }

    /** The index `dx` steps from `index` along a row of `n` that wraps: past the last comes the first, before the first the last. */
    private static int wrap(int index, int dx, int n) {
        return n <= 0 ? 0 : ((index + dx) % n + n) % n;
    }

    private static int nearest(RectF[] rects, int count, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(count, rects.length); i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    // MARK: - Continue Watching: the row under the shelf (Watching), reached with ↓ from the shelf

    /** The row's cards from the store (the sessions and Stremio's library); the same entries keep their texts and pictures. */
    private void updateWatching() {
        List<Watching.Entry> list = watching.list();
        boolean same = list.size() == watchItems.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i) == watchItems.get(i);
        if (same) return;
        watchItems = list;
        if (watchFocus.length != list.size()) {
            watchFocus = new Spring[list.size()];
            watchRects = new RectF[list.size()];
            for (int i = 0; i < watchFocus.length; i++) {
                watchFocus[i] = new Spring(0.3f, 0.82f, 0);
                watchRects[i] = new RectF();
            }
        }
        watchIndex = Math.max(0, Math.min(watchIndex, list.size() - 1));
        layoutWatch();
        if (list.isEmpty() && zone == Z_WATCH) closeWatch();
        retarget(true);
        watchDirty = true;
        spoken.focusMoved();
    }

    private void layoutWatch() {
        float left = MARGIN * k, top = W_TOP * k, cw = W_W * k, ch = W_H * k, gap = W_GAP * k;
        for (int i = 0; i < watchRects.length; i++) {
            float x = left + i * (cw + gap);
            watchRects[i].set(x, top, x + cw, top + ch);
            snap(watchRects[i]);
        }
        watchFirst = Math.max(0, Math.min(watchFirst, Math.max(0, watchItems.size() - W_VISIBLE)));
        watchScroll.snap(-watchFirst * (cw + gap));
        if (wTitle != null) for (Watching.Entry e : watchItems) watchText(e);
        watchDirty = true;
    }

    /** The two lines under a card, made once: the title (or the app, when it gave none); app · episode · time left. */
    private void watchText(Watching.Entry e) {
        fitPaint.set(wTitle);
        e.line1 = TextUtils.ellipsize(e.shownTitle(), fitPaint, W_W * k, TextUtils.TruncateAt.END).toString();
        StringBuilder s = new StringBuilder();
        boolean named = (e.title != null && !e.title.isEmpty()) || (e.pickTitle != null && !e.pickTitle.isEmpty());
        if (named) s.append(e.app);
        if (e.subtitle != null && !e.subtitle.isEmpty()) {
            if (s.length() > 0) s.append(" · ");
            s.append(e.subtitle);
        }
        String time = "";
        if (e.duration > 0) time = span(e.duration - e.position, true);
        else if (e.position > 0) time = span(e.position, false);
        if (!time.isEmpty()) {
            if (s.length() > 0) s.append(" · ");
            s.append(time);
        }
        fitPaint.set(wMeta);
        e.line2 = TextUtils.ellipsize(s, fitPaint, W_W * k, TextUtils.TruncateAt.END).toString();
    }

    /** "12 min left", "1 h 39 min left"; or, with no length known, "46 min in". */
    private static String span(long ms, boolean left) {
        long minutes = Math.max(1, Math.round(ms / 60000.0)), h = minutes / 60, m = minutes % 60;
        if (h == 0) return left ? Ui.trf("{n} min left", "n", minutes) : Ui.trf("{n} min in", "n", minutes);
        if (left) return m == 0 ? Ui.trf("{h} h left", "h", h) : Ui.trf("{h} h {m} min left", "h", h, "m", m);
        return m == 0 ? Ui.trf("{h} h in", "h", h) : Ui.trf("{h} h {m} min in", "h", h, "m", m);
    }

    /** Scrolls the row so the focused card shows (W_VISIBLE at a time; the next one peeks in at the edge). */
    private void revealWatch(boolean snap) {
        int n = watchItems.size();
        int first = Math.max(0, Math.min(Math.max(watchFirst, watchIndex - W_VISIBLE + 1), Math.min(watchIndex, Math.max(0, n - W_VISIBLE))));
        watchFirst = first;
        watchScroll.target = -first * (W_W + W_GAP) * k;
        if (snap) watchScroll.snap(watchScroll.target);
    }

    /** A card's place on screen (the row scrolled and lifted), for the opening animation and TalkBack. */
    private RectF watchRect(int i) {
        tmp.set(watchRects[i]);
        tmp.offset(watchScroll.value, watchLayer.getTranslationY());
        return tmp;
    }

    private int nearestWatch(float x) {
        int best = watchFirst;
        float bestDistance = Float.MAX_VALUE;
        for (int i = watchFirst; i < Math.min(watchRects.length, watchFirst + W_VISIBLE); i++) {
            float d = Math.abs(watchRects[i].centerX() + watchScroll.value - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return Math.max(0, Math.min(watchItems.size() - 1, best));
    }

    private void openWatch() {
        if (watchItems.isEmpty()) return;
        float x = zone == Z_DRAWER && drawerIndex < drawerRects.length ? drawerRects[drawerIndex].centerX()
                : !shelf.isEmpty() && shelfIndex < tileRects.length ? tileRects[shelfIndex].centerX() : w / 2f;
        peekOpen = true;
        peek.target = 1;
        zone = Z_WATCH;
        watchIndex = nearestWatch(x);
        for (int i = 0; i < watchFocus.length; i++) watchFocus[i].snap(i == watchIndex ? 1 : 0);
        watchDirty = true;
        motionDirty = true;
    }

    private void closeWatch() {
        peekOpen = false;
        peek.target = 0;
        zone = Z_SHELF;
        for (Spring f : watchFocus) f.snap(0);
        if (!shelf.isEmpty() && watchIndex < watchRects.length) shelfIndex = nearest(tileRects, watchRects[watchIndex].centerX() + watchScroll.value);
        motionDirty = true;
    }

    /** OK on a card: the title's page in its app (the app resumes it), or the app itself when there is no page to open. */
    private void openWatchEntry(Watching.Entry e, RectF r) {
        click.open();
        Recs.Pick p = e.pick();
        if (p != null) Watching.launched(getContext(), p, e.pkg, e.uri);   // the session that follows is this title again
        HomeApps.Item it = HomeApps.app(e.pkg, e.app, e.uri);
        boolean opened = HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top), Math.round(r.width()), Math.round(r.height()));
        if (!opened) showToast(Ui.trf("Couldn't open {app}.", "app", e.app));
    }

    /** The shelf's item for an app, when it is there with its logo and brand colors (the nameless card borrows its look). */
    private HomeApps.Item shelfItem(String pkg) {
        for (HomeApps.Item it : shelf) {
            if (pkg.equals(it.pkg) && it.logo != null && TILE_STYLE.containsKey(it.key)) return it;
        }
        return null;
    }

    private boolean canRemoveWatch() {
        return !covered() && zone == Z_WATCH && watchIndex < watchItems.size();
    }

    /** Hold OK on a card: one pill above it, to take it off the row. */
    private void openWatchMenu() {
        okHeld = true;
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        menuEntry = watchItems.get(watchIndex);
        menuActions.clear();
        menuActions.add(REMOVE_WATCH);
        menuPills = WATCH_MENU;
        menuTab = null;
        RectF r = watchRect(watchIndex);
        r.offset(0, 6 * k);   // openMenu lifts the card 6 px (the picks' focus); this row's cards rise 6 px of their own
        openMenu(null, r);
    }

    private void removeWatch(Watching.Entry e) {
        watching.hide(e);
        showToast(Ui.trf("Removed from Continue Watching: {title}", "title", e.shownTitle()));
        updateWatching();
    }

    /** An app's tile (logo on its brand color, or its banner) for a card the app gave no picture for; built off the UI thread. */
    private HomeApps.Item watchTile(Watching.Entry e) {
        if (watchTiles.containsKey(e.pkg)) return watchTiles.get(e.pkg);
        watchTiles.put(e.pkg, null);
        final String pkg = e.pkg, label = e.app;
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            final HomeApps.Item built = HomeApps.serviceByPackage(c, pkg, label);
            handler.post(() -> {
                watchTiles.put(pkg, built);
                watchDirty = true;
                wake();
            });
        });
        return null;
    }

    private void drawWatch(Canvas c) {
        if (watchItems.isEmpty() || wTitle == null) return;
        rowTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        c.drawText(Ui.tr("Continue Watching"), (MARGIN + 4) * k, W_TITLE_BASE * k, rowTitle);
        int top = -1;
        for (int i = 0; i < watchItems.size(); i++) if (watchFocus[i].value > 0.02f && (top < 0 || watchFocus[i].value > watchFocus[top].value)) top = i;
        float dx = watchScroll.value;
        c.save();
        c.translate(dx, 0);
        for (int i = 0; i < watchItems.size(); i++) if (i != top) drawWatchCard(c, i, dx);
        if (top >= 0) drawWatchCard(c, top, dx);
        c.restore();
    }

    /**
     * One card: the picture the app gave (else the TMDB title's it was opened as, else the app's tile), a progress bar
     * over a soft shade at the foot when the length is known, the thin bright rim when focused; under it the title and
     * app · episode · time left.
     */
    private void drawWatchCard(Canvas c, int i, float dx) {
        Watching.Entry e = watchItems.get(i);
        RectF base = watchRects[i];
        if (base.right + dx < -40 * k || base.left + dx > w + 40 * k) return;
        float f = Ui.clamp(watchFocus[i].value), s = 1 + 0.07f * f;
        float cx = base.centerX(), cy = base.centerY() - 6 * k * f;
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = W_RADIUS * k * s;
        tmp.set(cell);
        tmp.offset(0, (10 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * f));
        shadows.draw(c, tmp, r, DEEP_BLUR * k, shadowDeep);
        Bitmap img = null;
        BitmapShader sh = null;
        Watching.Art art = watching.art(e);
        if (art != null) {
            img = art.bitmap;
            sh = art.shader;
        } else {
            Recs.Pick p = e.pick();
            Recs.Thumb t = p != null && p.backdrop != null ? recs.thumb(p) : null;
            if (t != null) {
                img = t.bitmap;
                sh = t.shader;
            } else {
                HomeApps.Item it = watchTile(e);
                if (it != null && it.tile != null) {
                    img = it.tile;
                    sh = it.tileShader;
                }
            }
        }
        HomeApps.Item branded = img == null ? shelfItem(e.pkg) : null;
        if (branded != null) {   // no picture at all: the app's icon as the shelf draws it (its brand colors and logo), not its banner
            int[] style = TILE_STYLE.get(branded.key);
            float bw = base.width(), bh = base.height();
            LinearGradient g = watchGradients.get(e.pkg);
            if (g == null) {
                g = new LinearGradient(0, -bh / 2, 0, bh / 2, style[0], style[1], Shader.TileMode.CLAMP);
                watchGradients.put(e.pkg, g);
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            tileFill.setShader(g);
            c.drawRoundRect(tmp, W_RADIUS * k, W_RADIUS * k, tileFill);
            tileFill.setShader(null);
            Bitmap logo = style[2] == 2 && branded.logoDark != null ? branded.logoDark : branded.logo;
            float scale = Math.min(bw * 0.46f / logo.getWidth(), bh * 0.34f / logo.getHeight());
            float lw = logo.getWidth() * scale, lh = logo.getHeight() * scale;
            tmp.set(-lw / 2, -lh / 2, lw / 2, lh / 2);
            bitmapPaint.setColorFilter(style[2] == 1 ? whiteLogo : null);
            c.drawBitmap(logo, null, tmp, bitmapPaint);
            bitmapPaint.setColorFilter(null);
            c.restore();
        } else if (img != null) {
            roundImageCover(c, img, sh, cell, r);
        } else {
            fill.setShader(null);
            fill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
            c.drawRoundRect(cell, r, r, fill);
        }
        if (e.duration > 0) {
            if (wScrim == null) wScrim = new LinearGradient(0, 0, 0, 1, 0x00000000, 0x80000000, Shader.TileMode.CLAMP);
            float band = cell.height() * 0.42f;
            shaderMatrix.setScale(1, band);
            shaderMatrix.postTranslate(0, cell.bottom - band);
            wScrim.setLocalMatrix(shaderMatrix);
            fill.setShader(wScrim);
            c.drawRoundRect(cell, r, r, fill);
            fill.setShader(null);
            float m = 16 * k * s, bh = 5 * k * s, bw = cell.width() - 2 * m, by = cell.bottom - m - bh;
            fill.setColor(0x66FFFFFF);
            tmp.set(cell.left + m, by, cell.left + m + bw, by + bh);
            c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
            float frac = Ui.clamp((float) ((double) e.position / e.duration));
            if (frac > 0.005f) {
                fill.setColor(0xF7FFFFFF);
                tmp.right = tmp.left + Math.max(bh, bw * frac);
                c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
            }
        }
        border.setColor(tone(0x14000000, 0x14FFFFFF));
        c.drawRoundRect(cell, r, r, border);
        drawFocusRing(c, cell, r, f);
        float textA = menuEntry == e && menu.value > 0.001f ? 1 - Ui.clamp(menu.value) : 1;
        if (e.line1 == null) watchText(e);
        wTitle.setColor(Ui.alpha(tone(L_INK, WHITE), textA));
        c.drawText(e.line1, base.left, base.bottom + 44 * k, wTitle);
        if (!e.line2.isEmpty()) {
            wMeta.setColor(Ui.alpha(tone(L_DIM, 0xB3FFFFFF), textA));
            c.drawText(e.line2, base.left, base.bottom + 78 * k, wMeta);
        }
    }

    // MARK: - Helpers

    /** Rounds a shape's edges to whole pixels (crisp at rest; a scale animation moves them again). */
    private static void snap(RectF r) {
        r.set(Math.round(r.left), Math.round(r.top), Math.round(r.right), Math.round(r.bottom));
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = Ui.clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
