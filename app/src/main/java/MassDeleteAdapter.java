package com.hfm.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MassDeleteAdapter extends RecyclerView.Adapter<MassDeleteAdapter.ItemViewHolder> {

    private final Context context;
    private List<SearchResult> listItems;
    private final OnItemClickListener itemClickListener;
    
    // RESTORED: Executor for PDF/APK manual generation
    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(4);

    public interface OnItemClickListener {
        void onItemClick(SearchResult item);
    }

    public MassDeleteAdapter(Context context, List<SearchResult> listItems, OnItemClickListener itemClickListener) {
        this.context = context;
        this.listItems = listItems;
        this.itemClickListener = itemClickListener;
    }

    @NonNull
    @Override
    public ItemViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.grid_item_search_result, parent, false);
        return new ItemViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull final ItemViewHolder holder, int position) {
        final SearchResult item = listItems.get(position);

        holder.indexNumber.setText(String.valueOf(position + 1));
        holder.exclusionOverlay.setVisibility(item.isExcluded() ? View.GONE : View.VISIBLE);
        holder.thumbnailImage.setTag(item.getUri().toString());

        // Display Filename logic
        if (isMediaFile(item.getDisplayName())) {
            holder.fileNameText.setVisibility(View.GONE);
        } else {
            holder.fileNameText.setVisibility(View.VISIBLE);
            holder.fileNameText.setText(item.getDisplayName());
        }

        String displayName = item.getDisplayName();
        int fallbackIcon = getIconForFileType(displayName);

        // HYBRID LOADING STRATEGY:
        // 1. PDF/APK -> Use Manual Executor (Original Logic)
        // 2. Images/Videos -> Use Glide (New Logic)
        
        boolean isPdfOrApk = displayName != null && (displayName.toLowerCase().endsWith(".pdf") || displayName.toLowerCase().endsWith(".apk"));

        if (isPdfOrApk) {
            // Restore placeholder
            holder.thumbnailImage.setImageResource(fallbackIcon);
            
            thumbnailExecutor.execute(() -> {
                final Bitmap thumbnail = createSpecialThumbnail(item); // Uses restored methods
                if (thumbnail != null && holder.thumbnailImage.getTag().equals(item.getUri().toString())) {
                    holder.thumbnailImage.post(() -> holder.thumbnailImage.setImageBitmap(thumbnail));
                }
            });
        } else {
            // Use Glide for everything else (Images, Videos)
            Glide.with(context)
                .load(item.getUri())
                .apply(new RequestOptions()
                    .placeholder(fallbackIcon)
                    .error(fallbackIcon)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .centerCrop())
                .into(holder.thumbnailImage);
        }

        holder.itemView.setOnClickListener(v -> {
            if (itemClickListener != null) {
                itemClickListener.onItemClick(item);
            }
        });
    }

    private boolean isMediaFile(String fileName) {
        if (fileName == null) return false;
        String lowerFileName = fileName.toLowerCase();
        List<String> mediaExtensions = Arrays.asList(
            ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp",
            ".mp4", ".3gp", ".mkv", ".webm", ".avi"
        );

        for (String ext : mediaExtensions) {
            if (lowerFileName.endsWith(ext)) return true;
        }
        return false;
    }

    private int getIconForFileType(String fileName) {
        if (fileName == null) return R.drawable.ic_folder_modern;
        String lower = fileName.toLowerCase();
        
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) return android.R.drawable.ic_menu_save;
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return android.R.drawable.ic_menu_agenda;
        if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) return android.R.drawable.ic_menu_slideshow;
        if (lower.endsWith(".pdf")) return android.R.drawable.ic_menu_view;
        if (lower.endsWith(".txt") || lower.endsWith(".rtf") || lower.endsWith(".log")) return android.R.drawable.ic_menu_view;
        if (lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z")) return android.R.drawable.ic_menu_set_as;
        if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".ogg")) return android.R.drawable.ic_media_play;
        if (isMediaFile(fileName)) return android.R.drawable.ic_menu_gallery;
        
        // UPDATED: Modern yellow folder icon as fallback for non-media files/folders
        return R.drawable.ic_folder_modern;
    }

    // --- RESTORED METHODS FOR PDF/APK LOGIC ---

    private Bitmap createSpecialThumbnail(SearchResult item) {
        Uri uri = item.getUri();
        String displayName = item.getDisplayName();
        if (displayName == null) return null;
        String lower = displayName.toLowerCase();

        if (lower.endsWith(".apk")) {
            String path = "file".equals(uri.getScheme()) ? uri.getPath() : null;
            if (path != null) return getApkIcon(path);
        }
        if (lower.endsWith(".pdf")) {
            return createPdfThumbnail(uri);
        }
        return null;
    }

    private Bitmap getApkIcon(String filePath) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo pi = pm.getPackageArchiveInfo(filePath, 0);
            if (pi != null) {
                ApplicationInfo appInfo = pi.applicationInfo;
                appInfo.sourceDir = filePath;
                appInfo.publicSourceDir = filePath;
                Drawable icon = appInfo.loadIcon(pm);
                return drawableToBitmap(icon);
            }
        } catch (Exception e) {
            Log.e("MassDeleteAdapter", "Could not get APK icon", e);
        }
        return null;
    }

    private Bitmap createPdfThumbnail(Uri uri) {
        PdfRenderer renderer = null;
        PdfRenderer.Page page = null;
        ParcelFileDescriptor pfd = null;
        try {
            pfd = context.getContentResolver().openFileDescriptor(uri, "r");
            if (pfd == null) return null;
            renderer = new PdfRenderer(pfd);
            page = renderer.openPage(0);
            Bitmap bitmap = Bitmap.createBitmap(page.getWidth(), page.getHeight(), Bitmap.Config.ARGB_8888);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bitmap;
        } catch (Exception e) {
            Log.e("MassDeleteAdapter", "Could not render PDF thumbnail", e);
            return null;
        } finally {
            try {
                if (page != null) page.close();
                if (renderer != null) renderer.close();
                if (pfd != null) pfd.close();
            } catch (IOException ignored) {}
        }
    }

    private Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable instanceof BitmapDrawable) {
            return ((BitmapDrawable) drawable).getBitmap();
        }
        int width = drawable.getIntrinsicWidth() > 0 ? drawable.getIntrinsicWidth() : 96;
        int height = drawable.getIntrinsicHeight() > 0 ? drawable.getIntrinsicHeight() : 96;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    @Override
    public int getItemCount() {
        return listItems.size();
    }

    public void updateData(List<SearchResult> newItems) {
        this.listItems = newItems;
        notifyDataSetChanged();
    }

    public static class ItemViewHolder extends RecyclerView.ViewHolder {
        ImageView thumbnailImage;
        TextView indexNumber;
        View exclusionOverlay;
        TextView fileNameText;

        public ItemViewHolder(@NonNull View itemView) {
            super(itemView);
            thumbnailImage = itemView.findViewById(R.id.thumbnail_image);
            indexNumber = itemView.findViewById(R.id.index_number);
            exclusionOverlay = itemView.findViewById(R.id.exclusion_overlay);
            fileNameText = itemView.findViewById(R.id.file_name_text);
        }
    }

    public static class SearchResult {
        private final Uri uri;
        private final long mediaStoreId;
        private final String displayName;
        private boolean isExcluded;

        public SearchResult(Uri uri, long mediaStoreId, String displayName) {
            this.uri = uri;
            this.mediaStoreId = mediaStoreId;
            this.displayName = displayName;
            this.isExcluded = true;
        }
        public Uri getUri() { return uri; }
        public long getMediaStoreId() { return mediaStoreId; }
        public String getDisplayName() { return displayName; }
        public boolean isExcluded() { return isExcluded; }
        public void setExcluded(boolean excluded) { isExcluded = excluded; }
    }
}