package app.mdpreview.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.webkit.ValueCallback;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;

public final class MainActivity extends Activity {
    private static final int OPEN_DOCUMENT_REQUEST = 7;
    private static final int OPEN_TREE_REQUEST = 8;
    private static final String RECENT_PREFS = "recent";
    private static final String RECENT_FILES = "files";
    private static final String MARKDOWN_TREE_URI = "markdown_tree_uri";
    private static final String[] MARKDOWN_MIME_TYPES = new String[] {
        "text/markdown",
        "text/x-markdown",
        "text/md",
        "text/vnd.daringfireball.markdown",
        "application/markdown",
        "application/x-markdown",
        "application/md",
        "application/x-md",
        "application/vnd.daringfireball.markdown"
    };
    private static final String[] PICKER_MIME_TYPES = new String[] {
        "text/markdown",
        "text/x-markdown",
        "text/md",
        "text/vnd.daringfireball.markdown",
        "text/plain",
        "application/markdown",
        "application/x-markdown",
        "application/md",
        "application/x-md",
        "application/vnd.daringfireball.markdown"
    };
    private WebView webView;
    private Uri pendingUri;
    private final ArrayList<String> markdownBrowserBackStack = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);
        configureWebView();
        pendingUri = uriFromIntent(getIntent());
        webView.loadUrl("file:///android_asset/preview.html");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Uri uri = uriFromIntent(intent);
        if (uri != null) {
            openUri(uri);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OPEN_DOCUMENT_REQUEST && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                if (!isMarkdownDocument(uri)) {
                    showUnsupportedFile(uri);
                    return;
                }
                persistReadPermission(uri, data.getFlags());
                openUri(uri);
            }
        }
        if (requestCode == OPEN_TREE_REQUEST && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri != null) {
                persistTreePermission(treeUri, data.getFlags());
                recentPrefs().edit().putString(MARKDOWN_TREE_URI, treeUri.toString()).apply();
                markdownBrowserBackStack.clear();
                showMarkdownBrowser(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setBlockNetworkLoads(true);

        webView.addJavascriptInterface(new Bridge(), "MDPreviewAndroid");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                sendRecentToWeb();
                if (pendingUri != null) {
                    Uri uri = pendingUri;
                    pendingUri = null;
                    openUri(uri);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(Uri.parse(url));
            }
        });
    }

    @SuppressWarnings("deprecation")
    private Uri uriFromIntent(Intent intent) {
        if (intent == null) {
            return null;
        }
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            return intent.getData();
        }
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Parcelable stream;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                stream = intent.getParcelableExtra(Intent.EXTRA_STREAM, Parcelable.class);
            } else {
                stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            }
            if (stream instanceof Uri) {
                return (Uri) stream;
            }
        }
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> streams;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
            } else {
                streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            }
            if (streams != null && !streams.isEmpty()) {
                return streams.get(0);
            }
        }
        return null;
    }

    private void openUri(Uri uri) {
        openUri(uri, false);
    }

    private void openUri(Uri uri, boolean fromRecent) {
        try {
            if (!isMarkdownDocument(uri)) {
                showUnsupportedFile(uri);
                return;
            }
            byte[] bytes = readBytes(uri);
            String markdown = decodeMarkdown(bytes);
            String name = displayName(uri);
            JSONObject payload = new JSONObject();
            payload.put("markdown", markdown);
            payload.put("name", name);
            payload.put("baseHref", "file".equals(uri.getScheme()) ? baseHref(uri) : "");
            saveRecent(name, bytes);
            evaluate("window.MDPreview && window.MDPreview.render(" + payload + ");");
        } catch (IOException | RuntimeException | JSONException e) {
            if (fromRecent) {
                removeRecent(uri);
                Toast.makeText(this, "Recent file is no longer available", Toast.LENGTH_SHORT).show();
                return;
            }
            JSONObject payload = new JSONObject();
            try {
                payload.put("markdown", "Cannot read " + safeDisplayName(uri));
                payload.put("name", "Read error.md");
                payload.put("baseHref", "");
            } catch (JSONException ignored) {
            }
            evaluate("window.MDPreview && window.MDPreview.render(" + payload + ");");
        }
    }

    private byte[] readBytes(Uri uri) throws IOException {
        ContentResolver resolver = getContentResolver();
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) {
                throw new IOException("Cannot open input stream");
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private String decodeMarkdown(byte[] bytes) {
        if (bytes.length >= 3
            && (bytes[0] & 0xff) == 0xef
            && (bytes[1] & 0xff) == 0xbb
            && (bytes[2] & 0xff) == 0xbf) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xfe) {
            return StandardCharsets.UTF_16LE.decode(ByteBuffer.wrap(bytes, 2, bytes.length - 2)).toString();
        }
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xfe && (bytes[1] & 0xff) == 0xff) {
            return StandardCharsets.UTF_16BE.decode(ByteBuffer.wrap(bytes, 2, bytes.length - 2)).toString();
        }

        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try {
            CharBuffer chars = decoder.decode(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN));
            return chars.toString();
        } catch (CharacterCodingException ignored) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private String displayName(Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) {
                        String name = cursor.getString(index);
                        if (name != null && !name.isEmpty()) {
                            return name;
                        }
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        return fallbackDisplayName(uri);
    }

    private String safeDisplayName(Uri uri) {
        try {
            return displayName(uri);
        } catch (RuntimeException ignored) {
            return fallbackDisplayName(uri);
        }
    }

    private String fallbackDisplayName(Uri uri) {
        String path = uri.getLastPathSegment();
        if (path == null || path.isEmpty()) {
            return "Untitled.md";
        }
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private boolean isMarkdownDocument(Uri uri) {
        String name = safeDisplayName(uri).toLowerCase(java.util.Locale.ROOT);
        if (isMarkdownFileName(name)) {
            return true;
        }

        String mimeType = getContentResolver().getType(uri);
        return isMarkdownMimeType(mimeType);
    }

    private boolean isMarkdownEntry(String name, String mimeType) {
        if (name != null && isMarkdownFileName(name.toLowerCase(java.util.Locale.ROOT))) {
            return true;
        }
        return isMarkdownMimeType(mimeType);
    }

    private boolean isMarkdownFileName(String name) {
        return name.endsWith(".md")
            || name.endsWith(".markdown")
            || name.endsWith(".mdown")
            || name.endsWith(".mkd");
    }

    private boolean isMarkdownMimeType(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        String normalized = mimeType.toLowerCase(java.util.Locale.ROOT);
        for (String markdownMimeType : MARKDOWN_MIME_TYPES) {
            if (markdownMimeType.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private void showUnsupportedFile(Uri uri) {
        String name = safeDisplayName(uri);
        JSONObject payload = new JSONObject();
        try {
            payload.put("markdown", "Cannot open non-Markdown file: " + name);
            payload.put("name", "Unsupported file.md");
            payload.put("baseHref", "");
        } catch (JSONException ignored) {
        }
        Toast.makeText(this, "Choose a Markdown file", Toast.LENGTH_SHORT).show();
        evaluate("window.MDPreview && window.MDPreview.render(" + payload + ");");
    }

    private String baseHref(Uri uri) {
        String text = uri.toString();
        int slash = text.lastIndexOf('/');
        return slash >= 0 ? text.substring(0, slash + 1) : "";
    }

    @SuppressWarnings("deprecation")
    private void openDocumentPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, PICKER_MIME_TYPES);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, OPEN_DOCUMENT_REQUEST);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private void openDocumentTreePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(intent, OPEN_TREE_REQUEST);
        } catch (ActivityNotFoundException ignored) {
            openDocumentPicker();
        }
    }

    private void openMarkdownBrowser() {
        Uri treeUri = savedMarkdownTreeUri();
        if (treeUri == null) {
            showOpenSourceDialog();
            return;
        }
        String rootDocumentId;
        try {
            rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (IllegalArgumentException e) {
            recentPrefs().edit().remove(MARKDOWN_TREE_URI).apply();
            showOpenSourceDialog();
            return;
        }
        markdownBrowserBackStack.clear();
        showMarkdownBrowser(treeUri, rootDocumentId);
    }

    private Uri savedMarkdownTreeUri() {
        String raw = recentPrefs().getString(MARKDOWN_TREE_URI, "");
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        return Uri.parse(raw);
    }

    private void showOpenSourceDialog() {
        String[] options = new String[] {
            "Choose Markdown folder",
            "System picker"
        };
        new AlertDialog.Builder(this)
            .setTitle("Open Markdown")
            .setItems(options, (dialog, which) -> {
                if (which == 0) {
                    openDocumentTreePicker();
                    return;
                }
                openDocumentPicker();
            })
            .show();
    }

    private void showMarkdownBrowser(Uri treeUri, String documentId) {
        ArrayList<MarkdownBrowserItem> items;
        try {
            items = markdownBrowserItems(treeUri, documentId);
        } catch (RuntimeException e) {
            recentPrefs().edit().remove(MARKDOWN_TREE_URI).apply();
            Toast.makeText(this, "Choose a Markdown folder", Toast.LENGTH_SHORT).show();
            showOpenSourceDialog();
            return;
        }

        ArrayList<MarkdownBrowserItem> actions = new ArrayList<>();
        if (!markdownBrowserBackStack.isEmpty()) {
            actions.add(MarkdownBrowserItem.parent());
        }
        actions.addAll(items);
        if (actions.isEmpty()) {
            actions.add(MarkdownBrowserItem.empty());
        }

        CharSequence[] labels = new CharSequence[actions.size()];
        for (int i = 0; i < actions.size(); i++) {
            labels[i] = actions.get(i).label;
        }

        new AlertDialog.Builder(this)
            .setTitle("Markdown files")
            .setItems(labels, (dialog, which) -> {
                MarkdownBrowserItem item = actions.get(which);
                if (item.empty) {
                    return;
                }
                if (item.parent) {
                    String parentDocumentId = markdownBrowserBackStack.remove(markdownBrowserBackStack.size() - 1);
                    showMarkdownBrowser(treeUri, parentDocumentId);
                    return;
                }
                if (item.directory) {
                    markdownBrowserBackStack.add(documentId);
                    showMarkdownBrowser(treeUri, item.documentId);
                    return;
                }
                if (item.uri != null) {
                    openUri(item.uri);
                }
            })
            .setNeutralButton("Change folder", (dialog, which) -> openDocumentTreePicker())
            .setNegativeButton("System picker", (dialog, which) -> openDocumentPicker())
            .show();
    }

    private ArrayList<MarkdownBrowserItem> markdownBrowserItems(Uri treeUri, String documentId) {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        String[] projection = new String[] {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        };

        ArrayList<MarkdownBrowserItem> items = new ArrayList<>();
        try (Cursor cursor = getContentResolver().query(childrenUri, projection, null, null, null)) {
            if (cursor == null) {
                return items;
            }
            int idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            while (cursor.moveToNext()) {
                if (idIndex < 0 || nameIndex < 0 || mimeIndex < 0) {
                    continue;
                }
                String childDocumentId = cursor.getString(idIndex);
                String name = cursor.getString(nameIndex);
                String mimeType = cursor.getString(mimeIndex);
                if (childDocumentId == null || name == null || name.isEmpty()) {
                    continue;
                }
                Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocumentId);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                    items.add(MarkdownBrowserItem.directory("[Dir] " + name, childDocumentId));
                } else if (isMarkdownEntry(name, mimeType)) {
                    items.add(MarkdownBrowserItem.file(name, uri));
                }
            }
        }
        Collections.sort(items, (left, right) -> {
            if (left.directory != right.directory) {
                return left.directory ? -1 : 1;
            }
            return left.label.compareToIgnoreCase(right.label);
        });
        return items;
    }

    private void persistReadPermission(Uri uri, int flags) {
        int readFlag = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        int persistableFlag = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
        if ((flags & readFlag) == 0 || (flags & persistableFlag) == 0) {
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri, readFlag);
        } catch (SecurityException ignored) {
        }
    }

    private void persistTreePermission(Uri uri, int flags) {
        int readFlag = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if ((flags & readFlag) == 0) {
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri, readFlag);
        } catch (SecurityException ignored) {
        }
    }

    private void evaluate(String script) {
        webView.evaluateJavascript(script, (ValueCallback<String>) null);
    }

    private void openExternalUrl(String url) {
        Uri uri = Uri.parse(url);
        openExternalUri(uri);
    }

    private boolean handleNavigation(Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null || scheme.equalsIgnoreCase("file")) {
            return false;
        }
        if (scheme.equalsIgnoreCase("javascript")
            || scheme.equalsIgnoreCase("data")
            || scheme.equalsIgnoreCase("vbscript")) {
            return true;
        }
        if (scheme.equalsIgnoreCase("http")
            || scheme.equalsIgnoreCase("https")
            || scheme.equalsIgnoreCase("mailto")) {
            openExternalUri(uri);
            return true;
        }
        return true;
    }

    private void openExternalUri(Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null
            || (!scheme.equalsIgnoreCase("http")
            && !scheme.equalsIgnoreCase("https")
            && !scheme.equalsIgnoreCase("mailto"))) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private void printDocument() {
        PrintManager printManager = (PrintManager) getSystemService(Context.PRINT_SERVICE);
        if (printManager == null) {
            return;
        }
        PrintDocumentAdapter adapter = webView.createPrintDocumentAdapter(displayedTitle());
        PrintAttributes attributes = new PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .setMinMargins(new PrintAttributes.Margins(500, 500, 500, 500))
            .build();
        printManager.print(displayedTitle(), adapter, attributes);
    }

    private String displayedTitle() {
        CharSequence title = webView.getTitle();
        if (title == null || title.toString().trim().isEmpty()) {
            return "MD Preview";
        }
        return title.toString().replace(" - MD Preview", "");
    }

    private SharedPreferences recentPrefs() {
        return getSharedPreferences(RECENT_PREFS, MODE_PRIVATE);
    }

    private JSONArray recentFiles() {
        String raw = recentPrefs().getString(RECENT_FILES, "[]");
        try {
            return new JSONArray(raw);
        } catch (JSONException ignored) {
            return new JSONArray();
        }
    }

    private File recentDirectory() {
        File directory = new File(getFilesDir(), "RecentDocuments");
        if (!directory.exists()) {
            directory.mkdirs();
        }
        return directory;
    }

    private void saveRecent(String name, byte[] bytes) {
        String displayName = cleanRecentName(name);
        String fileName = UUID.randomUUID() + "-" + safeRecentFileName(displayName);
        File file = new File(recentDirectory(), fileName);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        } catch (IOException ignored) {
            return;
        }

        JSONArray previous = recentFiles();
        JSONArray next = new JSONArray();
        try {
            JSONObject current = new JSONObject();
            current.put("id", fileName);
            current.put("name", displayName);
            next.put(current);
            for (int i = 0; i < previous.length(); i++) {
                JSONObject item = previous.optJSONObject(i);
                if (item == null || displayName.equals(cleanRecentName(item.optString("name")))) {
                    deleteRecentCopy(item);
                    continue;
                }
                if (next.length() < 8) {
                    next.put(item);
                } else {
                    deleteRecentCopy(item);
                }
            }
        } catch (JSONException ignored) {
        }
        recentPrefs().edit().putString(RECENT_FILES, next.toString()).apply();
        sendRecentToWeb();
    }

    private void removeRecent(Uri uri) {
        JSONArray previous = recentFiles();
        JSONArray next = new JSONArray();
        String uriText = uri.toString();
        for (int i = 0; i < previous.length(); i++) {
            JSONObject item = previous.optJSONObject(i);
            if (item == null || uriText.equals(item.optString("id"))) {
                continue;
            }
            next.put(item);
        }
        recentPrefs().edit().putString(RECENT_FILES, next.toString()).apply();
        sendRecentToWeb();
    }

    private void removeRecentId(String id) {
        JSONArray previous = recentFiles();
        JSONArray next = new JSONArray();
        for (int i = 0; i < previous.length(); i++) {
            JSONObject item = previous.optJSONObject(i);
            if (item == null) {
                continue;
            }
            if (id.equals(item.optString("id"))) {
                deleteRecentCopy(item);
                continue;
            }
            next.put(item);
        }
        recentPrefs().edit().putString(RECENT_FILES, next.toString()).apply();
        sendRecentToWeb();
    }

    private JSONObject recentItem(String id) {
        JSONArray items = recentFiles();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && id.equals(item.optString("id"))) {
                return item;
            }
        }
        return null;
    }

    private boolean isLocalRecentId(String id) {
        return Uri.parse(id).getScheme() == null;
    }

    private void deleteRecentCopy(JSONObject item) {
        String id = item.optString("id");
        if (id.isEmpty() || !isLocalRecentId(id)) {
            return;
        }
        File file = new File(recentDirectory(), id);
        if (file.isFile()) {
            file.delete();
        }
    }

    private String cleanRecentName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "Untitled.md";
        }
        return name.trim();
    }

    private String safeRecentFileName(String name) {
        return cleanRecentName(name).replace("/", "-").replace("\\", "-");
    }

    private void openRecentId(String id) {
        if (!isLocalRecentId(id)) {
            openUri(Uri.parse(id), true);
            return;
        }
        JSONObject item = recentItem(id);
        File file = new File(recentDirectory(), id);
        if (item == null || !file.isFile()) {
            removeRecentId(id);
            Toast.makeText(this, "Recent file is no longer available", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            JSONObject payload = new JSONObject();
            payload.put("markdown", decodeMarkdown(bytes));
            payload.put("name", cleanRecentName(item.optString("name")));
            payload.put("baseHref", "");
            evaluate("window.MDPreview && window.MDPreview.render(" + payload + ");");
        } catch (IOException | RuntimeException | JSONException e) {
            removeRecentId(id);
            Toast.makeText(this, "Recent file is no longer available", Toast.LENGTH_SHORT).show();
        }
    }

    private void sendRecentToWeb() {
        evaluate("window.MDPreview && window.MDPreview.setRecent(" + recentFiles() + ");");
    }

    private static final class MarkdownBrowserItem {
        final String label;
        final Uri uri;
        final String documentId;
        final boolean directory;
        final boolean parent;
        final boolean empty;

        private MarkdownBrowserItem(
            String label,
            Uri uri,
            String documentId,
            boolean directory,
            boolean parent,
            boolean empty
        ) {
            this.label = label;
            this.uri = uri;
            this.documentId = documentId;
            this.directory = directory;
            this.parent = parent;
            this.empty = empty;
        }

        static MarkdownBrowserItem directory(String label, String documentId) {
            return new MarkdownBrowserItem(label, null, documentId, true, false, false);
        }

        static MarkdownBrowserItem file(String label, Uri uri) {
            return new MarkdownBrowserItem(label, uri, null, false, false, false);
        }

        static MarkdownBrowserItem parent() {
            return new MarkdownBrowserItem("..", null, null, false, true, false);
        }

        static MarkdownBrowserItem empty() {
            return new MarkdownBrowserItem("No Markdown files in this folder", null, null, false, false, true);
        }
    }

    private final class Bridge {
        @JavascriptInterface
        public void openFile() {
            runOnUiThread(MainActivity.this::openMarkdownBrowser);
        }

        @JavascriptInterface
        public void openExternal(String url) {
            runOnUiThread(() -> openExternalUrl(url));
        }

        @JavascriptInterface
        public void printDocument() {
            runOnUiThread(MainActivity.this::printDocument);
        }

        @JavascriptInterface
        public void getRecent() {
            runOnUiThread(MainActivity.this::sendRecentToWeb);
        }

        @JavascriptInterface
        public void openRecent(String uri) {
            runOnUiThread(() -> openRecentId(uri));
        }
    }
}
