package com.hfm.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ThumbnailUtils;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StorageBrowserAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> implements Filterable {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;

    private final Context context;
    private List<Object> masterList;
    private List<Object> filteredList;
    private final OnItemClickListener itemClickListener;
    private final OnHeaderCheckedChangeListener headerCheckedListener;
    private final OnHeaderClickListener headerClickListener; // NEW
    
    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(4);

    public interface OnItemClickListener {
        void onItemClick(int position, Object item);
        void onItemLongClick(int position, Object item);
        void onSelectionChanged();
    }

    public interface OnHeaderCheckedChangeListener {
        void onHeaderCheckedChanged(StorageBrowserActivity.DateHeader header, boolean isChecked);
    }

    // NEW: Interface for minimize/expand toggle
    public interface OnHeaderClickListener {
        void onHeaderClick(StorageBrowserActivity.DateHeader header);
    }

    public StorageBrowserAdapter(Context context, List<Object> items, 
                                 OnItemClickListener itemClickListener, 
                                 OnHeaderCheckedChangeListener headerCheckedListener,
                                 OnHeaderClickListener headerClickListener) {
        this.context = context;
        this.masterList = items;
        this.filteredList = new ArrayList<>(items);
        this.itemClickListener = itemClickListener;
        this.headerCheckedListener = headerCheckedListener;
        this.headerClickListener = headerClickListener;
    }

    public void updateMasterList(List<Object> newItems) {
        filteredList = new ArrayList<>(newItems);
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        if (filteredList.get(position) instanceof StorageBrowserActivity.DateHeader) {
            return TYPE_HEADER;
        } else {
            return TYPE_ITEM;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            View view = LayoutInflater.from(context).inflate(R.layout.list_item_date_header_browser, parent, false);
            return new HeaderViewHolder(view);
        } else {
            View view = LayoutInflater.from(context).inflate(R.layout.grid_item_file_delete, parent, false);
            return new FileViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull final RecyclerView.ViewHolder holder, int position) {
        int viewType = getItemViewType(position);
        if (viewType == TYPE_HEADER) {
            final HeaderViewHolder headerViewHolder = (HeaderViewHolder) holder;
            final StorageBrowserActivity.DateHeader dateHeader = (StorageBrowserActivity.DateHeader) filteredList.get(position);

            headerViewHolder.dateHeaderText.setText(dateHeader.getDateString());
            
            // UPDATED: Handle arrow rotation for minimize icon
            headerViewHolder.arrowIcon.setRotation(dateHeader.isExpanded() ? 0f : 180f);

            headerViewHolder.dateHeaderCheckbox.setOnCheckedChangeListener(null);
            headerViewHolder.dateHeaderCheckbox.setChecked(dateHeader.isChecked());
            headerViewHolder.dateHeaderCheckbox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
					@Override
					public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
						if (headerCheckedListener != null) {
							headerCheckedListener.onHeaderCheckedChanged(dateHeader, isChecked);
						}
					}
				});

            // Handle minimize toggle click
            headerViewHolder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (headerClickListener != null) {
                        headerClickListener.onHeaderClick(dateHeader);
                    }
                }
            });

        } else {
            final FileViewHolder fileViewHolder = (FileViewHolder) holder;
            final StorageBrowserAdapter.FileItem item = (StorageBrowserAdapter.FileItem) filteredList.get(position);
            final File file = item.getFile();

            fileViewHolder.fileName.setText(file.getName());
            fileViewHolder.thumbnailImage.setImageResource(android.R.color.darker_gray);
            fileViewHolder.thumbnailImage.setTag(file.getAbsolutePath());
            fileViewHolder.selectionOverlay.setVisibility(item.isSelected() ? View.VISIBLE : View.GONE);
            fileViewHolder.selectionCheckbox.setVisibility(file.isDirectory() ? View.GONE : View.VISIBLE);

            fileViewHolder.selectionCheckbox.setOnCheckedChangeListener(null);
            fileViewHolder.selectionCheckbox.setChecked(item.isSelected());
            fileViewHolder.selectionCheckbox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
					@Override
					public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
						item.setSelected(isChecked);
						fileViewHolder.selectionOverlay.setVisibility(isChecked ? View.VISIBLE : View.GONE);
						if (itemClickListener != null) {
							itemClickListener.onSelectionChanged();
						}
					}
				});

            fileViewHolder.itemView.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						if (itemClickListener != null) {
							itemClickListener.onItemClick(holder.getAdapterPosition(), item);
						}
					}
				});

            fileViewHolder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
					@Override
					public boolean onLongClick(View v) {
						if (itemClickListener != null) {
							itemClickListener.onItemLongClick(holder.getAdapterPosition(), item);
						}
						return true;
					}
				});

            if (file.isDirectory()) {
                // Modern yellow folder icon
                fileViewHolder.thumbnailImage.setImageResource(R.drawable.ic_folder_modern);
            } else {
                int fallbackIcon = getIconForFileType(file.getName());
                
                Glide.with(context)
                    .load(file)
                    .apply(new RequestOptions()
                        .placeholder(fallbackIcon)
                        .error(fallbackIcon)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .centerCrop())
                    .into(fileViewHolder.thumbnailImage);
            }
        }
    }

    private int getIconForFileType(String fileName) {
        String lowerFileName = fileName.toLowerCase();
        if (lowerFileName.endsWith(".doc") || lowerFileName.endsWith(".docx") || lowerFileName.endsWith(".pdf")) return android.R.drawable.ic_menu_save;
        if (lowerFileName.endsWith(".xls") || lowerFileName.endsWith(".xlsx")) return android.R.drawable.ic_menu_agenda;
        if (lowerFileName.endsWith(".ppt") || lowerFileName.endsWith(".pptx")) return android.R.drawable.ic_menu_slideshow;
        if (lowerFileName.endsWith(".txt") || lowerFileName.endsWith(".rtf") || lowerFileName.endsWith(".log")) return android.R.drawable.ic_menu_view;
        if (lowerFileName.endsWith(".html") || lowerFileName.endsWith(".xml") || lowerFileName.endsWith(".js") || lowerFileName.endsWith(".css") || lowerFileName.endsWith(".java") || lowerFileName.endsWith(".py") || lowerFileName.endsWith(".c") || lowerFileName.endsWith(".cpp") || lowerFileName.endsWith(".php")) return android.R.drawable.ic_menu_edit;
        if (lowerFileName.endsWith(".zip") || lowerFileName.endsWith(".rar") || lowerFileName.endsWith(".7z")) return android.R.drawable.ic_menu_set_as;
        if (lowerFileName.endsWith(".mp3") || lowerFileName.endsWith(".wav") || lowerFileName.endsWith(".ogg")) return android.R.drawable.ic_media_play;
        return android.R.drawable.ic_menu_info_details;
    }

    @Override
    public int getItemCount() {
        return filteredList.size();
    }

    @Override
    public Filter getFilter() {
        return new Filter() {
            @Override
            protected FilterResults performFiltering(CharSequence constraint) {
                String charString = constraint.toString().toLowerCase();
                List<Object> filterResultsList = new ArrayList<>();
                if (charString.isEmpty()) {
                    filterResultsList.addAll(masterList);
                } else {
                    for (Object item : masterList) {
                        if (item instanceof FileItem) {
                            File file = ((FileItem) item).getFile();
                            if (file.getName().toLowerCase().contains(charString)) {
                                filterResultsList.add(item);
                            }
                        }
                    }
                }
                FilterResults filterResults = new FilterResults();
                filterResults.values = filterResultsList;
                return filterResults;
            }

            @Override
            protected void publishResults(CharSequence constraint, FilterResults results) {
                filteredList = (ArrayList<Object>) results.values;
                notifyDataSetChanged();
            }
        };
    }

    public static class FileViewHolder extends RecyclerView.ViewHolder {
        ImageView thumbnailImage;
        TextView fileName;
        View selectionOverlay;
        CheckBox selectionCheckbox;

        public FileViewHolder(@NonNull View itemView) {
            super(itemView);
            thumbnailImage = itemView.findViewById(R.id.thumbnail_image_delete);
            fileName = itemView.findViewById(R.id.file_name_delete);
            selectionOverlay = itemView.findViewById(R.id.selection_overlay);
            selectionCheckbox = itemView.findViewById(R.id.selection_checkbox);
        }
    }

    public static class HeaderViewHolder extends RecyclerView.ViewHolder {
        TextView dateHeaderText;
        CheckBox dateHeaderCheckbox;
        ImageView arrowIcon; // NEW

        public HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            dateHeaderText = itemView.findViewById(R.id.date_header_text_browser);
            dateHeaderCheckbox = itemView.findViewById(R.id.date_header_checkbox_browser);
            arrowIcon = itemView.findViewById(R.id.header_arrow_browser); // NEW
        }
    }

    public static class FileItem {
        private File file;
        private boolean isSelected;

        public FileItem(File file) {
            this.file = file;
            this.isSelected = false;
        }

        public File getFile() { return file; }
        public boolean isSelected() { return isSelected; }
        public void setSelected(boolean selected) { isSelected = selected; }
    }
}