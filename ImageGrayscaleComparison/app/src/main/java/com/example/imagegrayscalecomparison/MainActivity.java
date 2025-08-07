package com.example.imagegrayscalecomparison;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileNotFoundException;

public class MainActivity extends Activity {
    
    private static final String TAG = "GrayscaleComparison";
    private static final int PICK_IMAGE1_REQUEST = 1;
    private static final int PICK_IMAGE2_REQUEST = 2;
    
    private ImageView imageView1, imageView2, grayscaleView1, grayscaleView2;
    private TextView resultText, progressText;
    private Button selectImage1Btn, selectImage2Btn, compareBtn;
    
    private Bitmap bitmap1, bitmap2;
    private Bitmap grayscaleBitmap1, grayscaleBitmap2;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        initViews();
        setupClickListeners();
    }
    
    private void initViews() {
        imageView1 = findViewById(R.id.imageView1);
        imageView2 = findViewById(R.id.imageView2);
        grayscaleView1 = findViewById(R.id.grayscaleView1);
        grayscaleView2 = findViewById(R.id.grayscaleView2);
        resultText = findViewById(R.id.resultText);
        progressText = findViewById(R.id.progressText);
        selectImage1Btn = findViewById(R.id.selectImage1Btn);
        selectImage2Btn = findViewById(R.id.selectImage2Btn);
        compareBtn = findViewById(R.id.compareBtn);
    }
    
    private void setupClickListeners() {
        selectImage1Btn.setOnClickListener(v -> selectImage(PICK_IMAGE1_REQUEST));
        selectImage2Btn.setOnClickListener(v -> selectImage(PICK_IMAGE2_REQUEST));
        compareBtn.setOnClickListener(v -> compareImages());
    }
    
    private void selectImage(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(intent, requestCode);
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (resultCode == RESULT_OK && data != null) {
            Uri imageUri = data.getData();
            try {
                Bitmap bitmap = BitmapFactory.decodeStream(getContentResolver().openInputStream(imageUri));
                
                if (requestCode == PICK_IMAGE1_REQUEST) {
                    bitmap1 = bitmap;
                    imageView1.setImageBitmap(bitmap1);
                    grayscaleBitmap1 = convertToGrayscale(bitmap1);
                    grayscaleView1.setImageBitmap(grayscaleBitmap1);
                    selectImage1Btn.setText("图片1已选择");
                } else if (requestCode == PICK_IMAGE2_REQUEST) {
                    bitmap2 = bitmap;
                    imageView2.setImageBitmap(bitmap2);
                    grayscaleBitmap2 = convertToGrayscale(bitmap2);
                    grayscaleView2.setImageBitmap(grayscaleBitmap2);
                    selectImage2Btn.setText("图片2已选择");
                }
                
                compareBtn.setEnabled(bitmap1 != null && bitmap2 != null);
                
            } catch (FileNotFoundException e) {
                Toast.makeText(this, "无法加载图片", Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Error loading image", e);
            }
        }
    }
    
    /**
     * 使用ColorMatrix将图片转换为灰度图像
     * 使用标准灰度转换公式：0.299*R + 0.587*G + 0.114*B
     */
    private Bitmap convertToGrayscale(Bitmap original) {
        if (original == null) return null;
        
        // 方法1: 使用ColorMatrix (推荐，性能更好)
        return convertToGrayscaleWithColorMatrix(original);
        
        // 方法2: 手动像素计算（仅供参考）
        // return convertToGrayscaleManually(original);
    }
    
    /**
     * 使用ColorMatrix转换为灰度图像（推荐方法）
     */
    private Bitmap convertToGrayscaleWithColorMatrix(Bitmap original) {
        ColorMatrix colorMatrix = new ColorMatrix();
        colorMatrix.setSaturation(0); // 设置饱和度为0即可得到灰度图像
        
        ColorMatrixColorFilter colorFilter = new ColorMatrixColorFilter(colorMatrix);
        
        Bitmap grayscaleBitmap = Bitmap.createBitmap(
            original.getWidth(), 
            original.getHeight(), 
            Bitmap.Config.ARGB_8888
        );
        
        Canvas canvas = new Canvas(grayscaleBitmap);
        Paint paint = new Paint();
        paint.setColorFilter(colorFilter);
        canvas.drawBitmap(original, 0, 0, paint);
        
        return grayscaleBitmap;
    }
    
    /**
     * 手动计算每个像素的灰度值（仅供学习参考，性能较差）
     */
    private Bitmap convertToGrayscaleManually(Bitmap original) {
        int width = original.getWidth();
        int height = original.getHeight();
        
        Bitmap grayscaleBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int pixel = original.getPixel(x, y);
                
                // 提取RGB分量
                int red = Color.red(pixel);
                int green = Color.green(pixel);
                int blue = Color.blue(pixel);
                int alpha = Color.alpha(pixel);
                
                // 使用标准灰度转换公式
                int gray = (int) (0.299 * red + 0.587 * green + 0.114 * blue);
                
                // 创建新的灰度像素
                int grayPixel = Color.argb(alpha, gray, gray, gray);
                grayscaleBitmap.setPixel(x, y, grayPixel);
            }
        }
        
        return grayscaleBitmap;
    }
    
    /**
     * 比较两张图片的灰度值
     */
    private void compareImages() {
        if (bitmap1 == null || bitmap2 == null) {
            Toast.makeText(this, "请先选择两张图片", Toast.LENGTH_SHORT).show();
            return;
        }
        
        progressText.setVisibility(View.VISIBLE);
        progressText.setText("正在比较图片灰度值...");
        
        // 在后台线程中进行比较
        new Thread(() -> {
            try {
                // 调整图片尺寸为相同大小以便比较
                Bitmap resizedBitmap1 = resizeBitmapToSameSize(grayscaleBitmap1, grayscaleBitmap2);
                Bitmap resizedBitmap2 = resizeBitmapToSameSize(grayscaleBitmap2, grayscaleBitmap1);
                
                // 方法1: 使用getPixels批量获取像素（推荐）
                ComparisonResult result1 = compareGrayscaleValuesBatch(resizedBitmap1, resizedBitmap2);
                
                // 方法2: 逐个像素比较（仅供参考）
                ComparisonResult result2 = compareGrayscaleValuesPixelByPixel(resizedBitmap1, resizedBitmap2);
                
                // 方法3: 统计直方图比较
                double histogramSimilarity = compareHistograms(resizedBitmap1, resizedBitmap2);
                
                // 在主线程中更新UI
                runOnUiThread(() -> {
                    progressText.setVisibility(View.GONE);
                    displayComparisonResults(result1, result2, histogramSimilarity);
                });
                
            } catch (Exception e) {
                Log.e(TAG, "Error comparing images", e);
                runOnUiThread(() -> {
                    progressText.setVisibility(View.GONE);
                    resultText.setText("比较过程中发生错误");
                });
            }
        }).start();
    }
    
    /**
     * 调整图片尺寸为相同大小
     */
    private Bitmap resizeBitmapToSameSize(Bitmap bitmap1, Bitmap bitmap2) {
        int width = Math.min(bitmap1.getWidth(), bitmap2.getWidth());
        int height = Math.min(bitmap1.getHeight(), bitmap2.getHeight());
        return Bitmap.createScaledBitmap(bitmap1, width, height, true);
    }
    
    /**
     * 方法1: 使用getPixels批量比较灰度值（推荐，性能最好）
     */
    private ComparisonResult compareGrayscaleValuesBatch(Bitmap bitmap1, Bitmap bitmap2) {
        int width = bitmap1.getWidth();
        int height = bitmap1.getHeight();
        int pixelCount = width * height;
        
        // 批量获取像素
        int[] pixels1 = new int[pixelCount];
        int[] pixels2 = new int[pixelCount];
        
        bitmap1.getPixels(pixels1, 0, width, 0, 0, width, height);
        bitmap2.getPixels(pixels2, 0, width, 0, 0, width, height);
        
        int identicalPixels = 0;
        long totalDifference = 0;
        int maxDifference = 0;
        
        for (int i = 0; i < pixelCount; i++) {
            // 提取灰度值（由于是灰度图像，R=G=B，所以只需要获取红色分量）
            int gray1 = Color.red(pixels1[i]);
            int gray2 = Color.red(pixels2[i]);
            
            int difference = Math.abs(gray1 - gray2);
            totalDifference += difference;
            
            if (difference == 0) {
                identicalPixels++;
            }
            
            if (difference > maxDifference) {
                maxDifference = difference;
            }
        }
        
        double similarity = (double) identicalPixels / pixelCount * 100;
        double averageDifference = (double) totalDifference / pixelCount;
        
        return new ComparisonResult(similarity, averageDifference, maxDifference, pixelCount, identicalPixels);
    }
    
    /**
     * 方法2: 逐个像素比较灰度值（性能较差，仅供参考）
     */
    private ComparisonResult compareGrayscaleValuesPixelByPixel(Bitmap bitmap1, Bitmap bitmap2) {
        int width = bitmap1.getWidth();
        int height = bitmap1.getHeight();
        int pixelCount = width * height;
        
        int identicalPixels = 0;
        long totalDifference = 0;
        int maxDifference = 0;
        
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int pixel1 = bitmap1.getPixel(x, y);
                int pixel2 = bitmap2.getPixel(x, y);
                
                // 提取灰度值
                int gray1 = Color.red(pixel1); // 灰度图像中R=G=B
                int gray2 = Color.red(pixel2);
                
                int difference = Math.abs(gray1 - gray2);
                totalDifference += difference;
                
                if (difference == 0) {
                    identicalPixels++;
                }
                
                if (difference > maxDifference) {
                    maxDifference = difference;
                }
            }
        }
        
        double similarity = (double) identicalPixels / pixelCount * 100;
        double averageDifference = (double) totalDifference / pixelCount;
        
        return new ComparisonResult(similarity, averageDifference, maxDifference, pixelCount, identicalPixels);
    }
    
    /**
     * 方法3: 比较灰度直方图
     */
    private double compareHistograms(Bitmap bitmap1, Bitmap bitmap2) {
        int[] histogram1 = calculateGrayscaleHistogram(bitmap1);
        int[] histogram2 = calculateGrayscaleHistogram(bitmap2);
        
        // 使用巴氏距离计算直方图相似度
        double sum1 = 0, sum2 = 0, sumProduct = 0;
        
        for (int i = 0; i < 256; i++) {
            sum1 += histogram1[i];
            sum2 += histogram2[i];
        }
        
        for (int i = 0; i < 256; i++) {
            double p1 = histogram1[i] / sum1;
            double p2 = histogram2[i] / sum2;
            sumProduct += Math.sqrt(p1 * p2);
        }
        
        return sumProduct * 100; // 转换为百分比
    }
    
    /**
     * 计算灰度直方图
     */
    private int[] calculateGrayscaleHistogram(Bitmap bitmap) {
        int[] histogram = new int[256];
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int pixel = bitmap.getPixel(x, y);
                int gray = Color.red(pixel); // 灰度图像中R=G=B
                histogram[gray]++;
            }
        }
        
        return histogram;
    }
    
    /**
     * 显示比较结果
     */
    private void displayComparisonResults(ComparisonResult result1, ComparisonResult result2, double histogramSimilarity) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 图片灰度值比较结果 ===\n\n");
        
        sb.append("【批量像素比较法】（推荐）\n");
        sb.append(String.format("相同像素: %d / %d\n", result1.identicalPixels, result1.totalPixels));
        sb.append(String.format("相似度: %.2f%%\n", result1.similarity));
        sb.append(String.format("平均差异: %.2f\n", result1.averageDifference));
        sb.append(String.format("最大差异: %d\n\n", result1.maxDifference));
        
        sb.append("【逐个像素比较法】\n");
        sb.append(String.format("相似度: %.2f%%\n", result2.similarity));
        sb.append(String.format("平均差异: %.2f\n\n", result2.averageDifference));
        
        sb.append("【直方图比较法】\n");
        sb.append(String.format("直方图相似度: %.2f%%\n\n", histogramSimilarity));
        
        // 判断图片是否相等
        if (result1.similarity > 95) {
            sb.append("✅ 结论: 两张图片的灰度值基本相等");
        } else if (result1.similarity > 80) {
            sb.append("⚡ 结论: 两张图片的灰度值相似度较高");
        } else {
            sb.append("❌ 结论: 两张图片的灰度值差异较大");
        }
        
        resultText.setText(sb.toString());
    }
    
    /**
     * 比较结果数据类
     */
    private static class ComparisonResult {
        final double similarity;
        final double averageDifference;
        final int maxDifference;
        final int totalPixels;
        final int identicalPixels;
        
        public ComparisonResult(double similarity, double averageDifference, int maxDifference, 
                              int totalPixels, int identicalPixels) {
            this.similarity = similarity;
            this.averageDifference = averageDifference;
            this.maxDifference = maxDifference;
            this.totalPixels = totalPixels;
            this.identicalPixels = identicalPixels;
        }
    }
}