package com.nnpg.glazed.modules.esp;

import minegame159.meteorclient.systems.modules.Module;
import minegame159.meteorclient.systems.modules.Categories;

public class KryptonSusChunk extends Module {
    public KryptonSusChunk() {
        // Xếp nó vào nhóm Render (hoặc World) cùng với các module ESP khác
        super(Categories.Render, "krypton-sus-chunk", "Phát hiện base địch bằng cách phân tích kích thước gói tin Chunk (từ Krypton).");
    }
}
