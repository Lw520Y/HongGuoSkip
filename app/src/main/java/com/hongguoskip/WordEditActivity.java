package com.hongguoskip;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** 词库管理界面（对应原版 WordEditActivity）：三类词增删与恢复默认，即时持久化。 */
public class WordEditActivity extends Activity {

    private Spinner spinner;
    private EditText input;
    private WordAdapter adapter;
    private List<String> current;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_word_edit);

        spinner = findViewById(R.id.typeSpinner);
        input = findViewById(R.id.wordInput);
        ListView list = findViewById(R.id.wordList);
        adapter = new WordAdapter();
        list.setAdapter(adapter);

        ArrayAdapter<String> sa = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, WordStore.TYPE_NAMES);
        sa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(sa);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                reload();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        findViewById(R.id.btnAdd).setOnClickListener(v -> addWord());
        findViewById(R.id.btnReset).setOnClickListener(v -> confirmReset());
        reload();
    }

    private int type() {
        int pos = spinner.getSelectedItemPosition();
        return pos < 0 ? WordStore.TYPE_MARKER : pos;
    }

    private void reload() {
        current = WordStore.get(this, type());
        adapter.notifyDataSetChanged();
        setTitle(getString(R.string.word_edit_title) + " · " + current.size() + " 词");
    }

    private void addWord() {
        String w = input.getText().toString().trim();
        if (w.isEmpty()) {
            toast("请输入关键词");
            return;
        }
        if (WordStore.add(this, type(), w)) {
            input.setText("");
            toast("已添加：" + w);
        } else {
            toast("已存在或无效：" + w);
        }
        reload();
    }

    private void confirmReset() {
        int t = type();
        new AlertDialog.Builder(this)
                .setTitle("恢复默认")
                .setMessage("将把「" + WordStore.TYPE_NAMES[t] + "」恢复为默认词库（"
                        + WordStore.get(this, t).size() + " → " + defaultsCount(t) + " 词），继续吗？")
                .setPositiveButton("恢复", (d, w) -> {
                    WordStore.reset(this, t);
                    reload();
                    toast("已恢复默认词库");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int defaultsCount(int t) {
        switch (t) {
            case WordStore.TYPE_ECOM:
                return WordStore.DEFAULT_ECOM.length;
            case WordStore.TYPE_WEAK:
                return WordStore.DEFAULT_WEAK.length;
            default:
                return WordStore.DEFAULT_MARKER.length;
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private class WordAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return current == null ? 0 : current.size();
        }

        @Override
        public Object getItem(int position) {
            return current.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : LayoutInflater.from(WordEditActivity.this).inflate(R.layout.item_word, parent, false);
            TextView tv = v.findViewById(R.id.wordText);
            tv.setText(current.get(position));
            v.findViewById(R.id.btnDelete).setOnClickListener(b -> {
                WordStore.remove(WordEditActivity.this, type(), current.get(position));
                reload();
            });
            return v;
        }
    }
}
