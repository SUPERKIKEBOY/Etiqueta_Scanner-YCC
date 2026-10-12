package com.er.nyxhardwaretest;

import android.Manifest;
import androidx.activity.ComponentActivity;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends ComponentActivity {
    private PreviewView preview;
    private TextView status, fields, count;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private BarcodeScanner barcodeScanner;
    private TextRecognizer textRecognizer;
    private DB db;
    private String currentHU = "";
    private String np = "", dado = "", maquina = "", cantidad = "", fecha = "";
    private boolean busy = false, saved = false;
    private long lastProcessed = 0;
    private static final Pattern HU_CFM = Pattern.compile("\\bCFM[A-Z0-9]{7,18}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HU_NUM = Pattern.compile("(?<!\\d)5\\d{8,11}(?!\\d)");

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        db = new DB();
        barcodeScanner = BarcodeScanning.getClient();
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        buildUI();
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 10);
        else startCamera();
    }
    private void buildUI() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(12,12,12,12); root.setBackgroundColor(Color.rgb(245,247,250));
        TextView title = new TextView(this); title.setText("ETIQUETA SCANNER  V1"); title.setTextSize(20); title.setTextColor(Color.rgb(15,45,75)); title.setGravity(Gravity.CENTER); root.addView(title);
        preview = new PreviewView(this); root.addView(preview,new LinearLayout.LayoutParams(-1,0,5));
        status = new TextView(this); status.setText("Esperando etiqueta..."); status.setTextSize(16); status.setPadding(4,12,4,8); root.addView(status);
        fields = new TextView(this); fields.setTextSize(16); fields.setTextColor(Color.BLACK); fields.setPadding(8,8,8,8); root.addView(fields);
        count = new TextView(this); count.setTextSize(14); root.addView(count);
        Button history = new Button(this); history.setText("Ver últimos registros"); history.setOnClickListener(v -> showHistory()); root.addView(history);
        setContentView(root); refresh();
    }
    private void refresh() {
        fields.setText("HU: " + dash(currentHU) + "\nNP: " + dash(np) + "    Dado: " + dash(dado) + "\nMáquina: " + dash(maquina) + "\nCantidad: " + dash(cantidad) + "    Fecha: " + dash(fecha));
        count.setText("Registros guardados: " + db.total());
    }
    private String dash(String s) { return s.isEmpty() ? "—" : s; }
    @Override public void onRequestPermissionsResult(int req,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(req,permissions,grants);
        if (req == 10 && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else status.setText("Se requiere permiso de cámara");
    }
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get(); provider.unbindAll();
                Preview p = new Preview.Builder().build(); p.setSurfaceProvider(preview.getSurfaceProvider());
                ImageAnalysis analysis = new ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                analysis.setAnalyzer(executor, this::analyze);
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, p, analysis);
            } catch (Exception e) { status.setText("Error cámara: " + e.getMessage()); }
        }, ContextCompat.getMainExecutor(this));
    }
    @androidx.camera.core.ExperimentalGetImage
    private void analyze(ImageProxy proxy) {
        if (busy || System.currentTimeMillis()-lastProcessed < 280) {proxy.close();return;}
        if (proxy.getImage()==null) {proxy.close();return;}
        busy = true; lastProcessed = System.currentTimeMillis();
        InputImage image = InputImage.fromMediaImage(proxy.getImage(), proxy.getImageInfo().getRotationDegrees());
        // Both recognizers use the same frame. Never close ImageProxy until both have finished.
        com.google.android.gms.tasks.Task<List<Barcode>> bt = barcodeScanner.process(image);
        com.google.android.gms.tasks.Task<com.google.mlkit.vision.text.Text> tt = textRecognizer.process(image);
        com.google.android.gms.tasks.Tasks.whenAllComplete(bt,tt).addOnCompleteListener(task -> {
            try {
                StringBuilder codes = new StringBuilder();
                if (bt.isSuccessful()) for (Barcode b : bt.getResult()) if (b.getRawValue()!=null) codes.append(b.getRawValue()).append("\n");
                String text = tt.isSuccessful() ? tt.getResult().getText() : "";
                String rawCodes = codes.toString();
                main.post(() -> process(rawCodes,text));
            } finally {proxy.close();busy=false;}
        });
    }
    private void process(String codes,String ocr) {
        String hu = findHU(codes);
        if (hu.isEmpty()) hu = findHU(ocr);
        if (hu.isEmpty()) return; // Do not use OCR fields without a HU from the same image.
        if (!hu.equals(currentHU)) {
            currentHU=hu; np=""; dado=""; maquina=""; cantidad=""; fecha=""; saved=false;
            status.setText("Leyendo HU: " + hu);
        }
        if (saved) return;
        String text = ocr.toUpperCase(Locale.ROOT).replace('\r','\n');
        String found;
        // Recognize labels before using fallbacks to avoid confusing HU and NP.
        found = match(text,"(?:YPN|PART\\s*(?:NO|NUMBER|N[O°])|NP|P/N)\\s*[:#.-]?\\s*([S]?[17]\\d{7,11})");
        if (found.isEmpty()) found = match(text,"(?<![A-Z0-9])([17]\\d{7,9})(?!\\d)");
        if (!found.isEmpty()) np=found;
        found = match(text,"(?:MACHINE|MAQUINA|MÁQUINA|WC)\\s*[:#.-]?\\s*([A-Z0-9]{6,12})");
        if (found.isEmpty()) found = match(text,"\\b([EF][0-9A-Z]{7,9})\\b");
        if (!found.isEmpty()) maquina=found;
        found = match(text,"(?:DADO|DIE|MOLD)\\s*[:#.-]?\\s*([A-Z0-9]{1,5})");
        if (!found.isEmpty()) dado=found;
        found = match(text,"(?:QUANTITY|QTY|CANTIDAD|UNIDAD)\\s*[:#.-]?\\s*([0-9][0-9,.]{0,7})");
        if (!found.isEmpty()) cantidad=found.replaceAll("[^0-9]","");
        // DATE, not INJ DATE. For small labels accept Fecha de Lote.
        found = match(text,"(?:FECHA\\s*DE\\s*LOTE|(?<!INJ )\\bDATE)\\s*[:#.-]?\\s*([A-Z]{3,9}\\s+\\d{1,2},?\\s+\\d{4}|\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{4}|\\d{4}[/.-]\\d{1,2}[/.-]\\d{1,2})");
        if (!found.isEmpty()) fecha=normalizeDate(found);
        if (!np.isEmpty() && !dado.isEmpty() && !maquina.isEmpty() && !cantidad.isEmpty() && !fecha.isEmpty()) {
            boolean inserted = db.insert(currentHU,np,dado,maquina,cantidad,fecha);
            saved=true; status.setText(inserted ? "GUARDADO: " + currentHU : "HU DUPLICADA: " + currentHU);
        }
        refresh();
    }
    private String findHU(String input) {
        String clean = input.toUpperCase(Locale.ROOT).replace("*", "");
        Matcher m=HU_CFM.matcher(clean); if(m.find()) return m.group();
        m=HU_NUM.matcher(clean); return m.find()?m.group():"";
    }
    private String match(String s,String regex) { Matcher m=Pattern.compile(regex,Pattern.CASE_INSENSITIVE).matcher(s); return m.find()?m.group(1).trim():""; }
    private String normalizeDate(String value) {
        String[] formats={"MMM d, yyyy","MMM d yyyy","MMMM d, yyyy","MMMM d yyyy","MM/dd/yyyy","M/d/yyyy","yyyy-MM-dd","dd/MM/yyyy"};
        for(String fmt:formats) try {
            SimpleDateFormat f=new SimpleDateFormat(fmt,Locale.ENGLISH); f.setLenient(false);
            Date d=f.parse(value); if(d!=null) return new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(d);
        } catch(Exception ignored) {}
        return "";
    }
    private void showHistory() {
        Cursor c=db.getReadableDatabase().rawQuery("SELECT hu,np,maquina,fecha FROM etiquetas ORDER BY id DESC LIMIT 30",null);
        StringBuilder s=new StringBuilder();
        while(c.moveToNext()) s.append(c.getString(0)).append(" | ").append(c.getString(1)).append(" | ").append(c.getString(2)).append(" | ").append(c.getString(3)).append("\n");
        c.close(); new android.app.AlertDialog.Builder(this).setTitle("Últimos registros").setMessage(s.length()==0?"Sin registros":s.toString()).setPositiveButton("Cerrar",null).show();
    }
    private class DB extends SQLiteOpenHelper {
        DB(){super(MainActivity.this,"etiquetas_v1.db",null,1);}
        @Override public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE etiquetas (id INTEGER PRIMARY KEY AUTOINCREMENT,hu TEXT NOT NULL UNIQUE,np TEXT NOT NULL,dado TEXT NOT NULL,maquina TEXT NOT NULL,cantidad INTEGER NOT NULL,fecha TEXT NOT NULL,escaneado TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");}
        @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){}
        boolean insert(String hu,String np,String dado,String maquina,String cantidad,String fecha){
            ContentValues v=new ContentValues();v.put("hu",hu);v.put("np",np);v.put("dado",dado);v.put("maquina",maquina);v.put("cantidad",Integer.parseInt(cantidad));v.put("fecha",fecha);
            return getWritableDatabase().insertWithOnConflict("etiquetas",null,v,SQLiteDatabase.CONFLICT_IGNORE)!=-1;
        }
        int total(){Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM etiquetas",null);int n=c.moveToFirst()?c.getInt(0):0;c.close();return n;}
    }
    @Override protected void onDestroy(){super.onDestroy();barcodeScanner.close();textRecognizer.close();executor.shutdown();db.close();}
}
