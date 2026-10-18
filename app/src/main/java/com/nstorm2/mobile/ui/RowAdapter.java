package com.nstorm2.mobile.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.nstorm2.mobile.R;

import java.util.ArrayList;
import java.util.List;

/** Minimal RecyclerView adapter for "badge / title / subtitle" rows. */
public final class RowAdapter extends RecyclerView.Adapter<RowAdapter.Holder> {

    public static final class Row {
        public final String badge;
        public final String title;
        public final String subtitle;
        public final Object tag;

        public Row(String badge, String title, String subtitle, Object tag) {
            this.badge = badge;
            this.title = title;
            this.subtitle = subtitle;
            this.tag = tag;
        }
    }

    public interface OnRowClick {
        void onClick(Row row);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView badge, title, subtitle;

        Holder(View v) {
            super(v);
            badge = v.findViewById(R.id.badge);
            title = v.findViewById(R.id.title);
            subtitle = v.findViewById(R.id.subtitle);
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final OnRowClick click;

    public RowAdapter(OnRowClick click) {
        this.click = click;
    }

    public void setRows(List<Row> newRows) {
        rows.clear();
        rows.addAll(newRows);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_asset, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Row r = rows.get(position);
        h.badge.setText(r.badge);
        h.title.setText(r.title);
        h.subtitle.setText(r.subtitle);
        h.itemView.setOnClickListener(v -> click.onClick(r));
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }
}
