package com.example.photopaint.views.components.paint;

import android.graphics.*;
import android.opengl.GLES20;
import com.example.photopaint.helpers.DispatchQueue;
import com.example.photopaint.views.components.Size;

import javax.microedition.khronos.opengles.GL10;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Map;
import java.util.UUID;

public class Painting {

    public interface PaintingDelegate {
        void contentChanged(RectF rect);
        void strokeCommited();
        UndoStore requestUndoStore();
        DispatchQueue requestDispatchQueue();
    }

    public class PaintingData {
        public Bitmap bitmap;
        public ByteBuffer data;

        PaintingData(Bitmap b, ByteBuffer buffer) {
            bitmap = b;
            data = buffer;
        }
    }

    private PaintingDelegate delegate;
    private Path activePath;
    private RenderState renderState;
    private RenderView renderView;
    private Size size;
    private RectF activeStrokeBounds;
    private Brush brush;
    private Texture brushTexture;
    private Texture bitmapTexture;
    private ByteBuffer vertexBuffer;
    private ByteBuffer textureBuffer;
    private int reusableFramebuffer;
    private int paintTexture;
    private Map<String, Shader> shaders;
    private int suppressChangesCounter;
    private int[] buffers = new int[1];
    private int[] recoverBuffers = new int[1];
    private ByteBuffer dataBuffer;
    private ByteBuffer recoverDataBuffer;

    private boolean paused;
    private Slice backupSlice;

    private float projection[];
    private float renderProjection[];

    public Painting(Size sz) {
        renderState = new RenderState();

        size = sz;

        dataBuffer = ByteBuffer.allocateDirect((int)size.width * (int)size.height * 4);
        recoverDataBuffer = ByteBuffer.allocateDirect((int) size.width * (int) size.height * 4);

        projection = GLMatrix.LoadOrtho(0, size.width, 0, size.height, -1.0f, 1.0f);

        if (vertexBuffer == null) {
            vertexBuffer = ByteBuffer.allocateDirect(8 * 4);
            vertexBuffer.order(ByteOrder.nativeOrder());
        }
        vertexBuffer.putFloat(0.0f);
        vertexBuffer.putFloat(0.0f);
        vertexBuffer.putFloat(size.width);
        vertexBuffer.putFloat(0.0f);
        vertexBuffer.putFloat(0.0f);
        vertexBuffer.putFloat(size.height);
        vertexBuffer.putFloat(size.width);
        vertexBuffer.putFloat(size.height);
        vertexBuffer.rewind();

        if (textureBuffer == null) {
            textureBuffer = ByteBuffer.allocateDirect(8 * 4);
            textureBuffer.order(ByteOrder.nativeOrder());
            textureBuffer.putFloat(0.0f);
            textureBuffer.putFloat(0.0f);
            textureBuffer.putFloat(1.0f);
            textureBuffer.putFloat(0.0f);
            textureBuffer.putFloat(0.0f);
            textureBuffer.putFloat(1.0f);
            textureBuffer.putFloat(1.0f);
            textureBuffer.putFloat(1.0f);
            textureBuffer.rewind();
        }
    }

    public void setDelegate(PaintingDelegate paintingDelegate) {
        delegate = paintingDelegate;
    }

    public void setRenderView(RenderView view) {
        renderView = view;
    }

    public Size getSize() {
        return size;
    }

    public RectF getBounds() {
        return new RectF(0.0f, 0.0f, size.width, size.height);
    }

    private boolean isSuppressingChanges() {
        return suppressChangesCounter > 0;
    }

    private void beginSuppressingChanges() {
        suppressChangesCounter++;
    }

    private void endSuppressingChanges() {
        suppressChangesCounter--;
    }

    public void setBitmap(Bitmap bitmap) {
        if (bitmapTexture != null) {
            return;
        }

        // 创建原图的纹理
        bitmapTexture = new Texture(bitmap);
    }

    private void update(RectF bounds, Runnable action) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, getReusableFramebuffer());
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, getTexture(), 0);

        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (status == GLES20.GL_FRAMEBUFFER_COMPLETE) {
            GLES20.glViewport(0, 0, (int) size.width, (int) size.height);
            action.run();
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        if (!isSuppressingChanges() && delegate != null) {
            delegate.contentChanged(bounds);
        }
    }

    public void paintStroke(final Path path, final boolean clearBuffer, final Runnable action) {
        renderView.performInContext(new Runnable() {
            @Override
            public void run() {
                activePath = path;

                RectF bounds = null;

                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, getReusableFramebuffer());
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, getPaintTexture(), 0);

                Utils.HasGLError();

                int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
                if (status == GLES20.GL_FRAMEBUFFER_COMPLETE) {
                    GLES20.glViewport(0, 0, (int) size.width, (int) size.height);

                    if (clearBuffer) {
                        GLES20.glClearColor(0, 0, 0, 0);
                        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                    }

                    if (shaders == null) {
                        return;
                    }
                    Shader shader = shaders.get(brush.isMosaic() ? "mosaicBrush" : (brush.isLightSaber() ? "brushLight" : "brush"));
                    if (shader == null) {
                        return;
                    }

                    GLES20.glUseProgram(shader.program);
                    if (brushTexture == null) {
                        brushTexture = new Texture(brush.getStamp());//获取画刷的texture
                    }
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, brushTexture.texture());
                    GLES20.glUniformMatrix4fv(shader.getUniform("mvpMatrix"), 1, false, FloatBuffer.wrap(projection));
                    GLES20.glUniform1i(shader.getUniform("texture"), 0);

                    bounds = Render.RenderPath(path, renderState);
                }

                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

                if (delegate != null) {
                    delegate.contentChanged(bounds);
                }

                if (activeStrokeBounds != null) {
                    activeStrokeBounds.union(bounds);
                } else {
                    activeStrokeBounds = bounds;
                }

                if (action != null) {
                    action.run();
                }
            }
        });
    }

    public void commitStroke(final int color) {
        final UUID uuid = UUID.randomUUID();
        renderView.performInContext(new Runnable() {
            @Override
            public void run() {
                registerUndo(activeStrokeBounds,uuid);

                beginSuppressingChanges();

                update(null, new Runnable() {
                    @Override
                    public void run() {
                        if (shaders == null) {
                            return;
                        }
                        Shader shader = shaders.get(brush.isMosaic() ? "compositeWithMosaic" : (brush.isLightSaber() ? "compositeWithMaskLight" : "compositeWithMask"));
                        if (shader == null) {
                            return;
                        }

                        GLES20.glUseProgram(shader.program);

                        GLES20.glUniformMatrix4fv(shader.getUniform("mvpMatrix"), 1, false, FloatBuffer.wrap(projection));
                        GLES20.glUniform1i(shader.getUniform("mask"), 0);
                        Shader.SetColorUniform(shader.getUniform("color"), color);

                        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, getPaintTexture());

                        GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA, GLES20.GL_SRC_ALPHA, GLES20.GL_ONE );

                        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer);
                        GLES20.glEnableVertexAttribArray(0);
                        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 8, textureBuffer);
                        GLES20.glEnableVertexAttribArray(1);

                        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

                        registerRecover(activeStrokeBounds, uuid);
                    }
                });

                endSuppressingChanges();

                renderState.reset();

                activeStrokeBounds = null;
                activePath = null;
            }
        });
    }

    private void registerUndo(RectF rect, UUID uuid) {
        if (rect == null) {
            return;
        }

        boolean intersect = rect.setIntersect(rect, getBounds());
        if (!intersect) {
            return;
        }


        PaintingData paintingData = getPaintingData(rect, true);
        ByteBuffer data = paintingData.data;
        // 获取这部分paintingData并把它存储下来

        final Slice slice = new Slice(data, rect, delegate.requestDispatchQueue());
        delegate.requestUndoStore().registerUndo(uuid, new Runnable() {
            @Override
            public void run() {
                restoreSlice(slice);
            }
        });
    }

    private void registerRecover(RectF rect, UUID uuid){
        if (rect == null) {
            return;
        }

        boolean intersect = rect.setIntersect(rect, getBounds());
        if (!intersect) {
            return;
        }

        PaintingData recoverPaintingData = getRecoverPaintingData(rect);
        ByteBuffer data2 = recoverPaintingData.data;
        final Slice slice = new Slice(data2, rect, delegate.requestDispatchQueue());
        delegate.requestUndoStore().registerRecover(uuid, new Runnable() {
            @Override
            public void run() {
                recoverSlice(slice);
            }
        });
    }

    private void restoreSlice(final Slice slice) {
        renderView.performInContext(new Runnable() {
            @Override
            public void run() {
                ByteBuffer buffer = slice.getData();

                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, getTexture());
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, slice.getX(), slice.getY(), slice.getWidth(), slice.getHeight(), GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer);
                if (!isSuppressingChanges() && delegate != null) {
                    delegate.contentChanged(slice.getBounds());
                }

                // TODO:slice 的回收操作会导致图画紊乱，所以暂时没想好临时文件的回收时机，可以在退出图片编辑的时候回收
//                slice.cleanResources();
            }
        });
    }

    private void recoverSlice(final Slice slice){
        renderView.performInContext(new Runnable() {
            @Override
            public void run() {
                ByteBuffer buffer = slice.getData();

                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, getTexture());
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, slice.getX(), slice.getY(), slice.getWidth(), slice.getHeight(), GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer);
                if (!isSuppressingChanges() && delegate != null) {
                    delegate.contentChanged(slice.getBounds());
                }

                // TODO:slice 的回收操作会导致图画紊乱，所以暂时没想好临时文件的回收时机，可以在退出图片编辑的时候回收
//                slice.cleanResources();
            }
        });
    }

    public void setRenderProjection(float[] proj) {
        renderProjection = proj;
    }

    public void render() {
        // 绘制
        if (shaders == null) {
            return;
        }

        if (activePath != null) {
            render(getPaintTexture(), activePath.getColor());
        } else {
            renderBlit();
        }
    }

    private void render(int mask, int color) {
        Shader shader = shaders.get(brush.isMosaic() ? "blitWithMosaic" : (brush.isLightSaber() ? "blitWithMaskLight" : "blitWithMask"));
        if (shader == null) {
            return;
        }

        GLES20.glUseProgram(shader.program);

        GLES20.glUniformMatrix4fv(shader.getUniform("mvpMatrix"), 1, false, FloatBuffer.wrap(renderProjection));
        GLES20.glUniform1i(shader.getUniform("texture"), 0);
        GLES20.glUniform1i(shader.getUniform("mask"), 1);
        Shader.SetColorUniform(shader.getUniform("color"), color);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, getTexture());

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mask);

        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer);
        GLES20.glEnableVertexAttribArray(0);
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 8, textureBuffer);
        GLES20.glEnableVertexAttribArray(1);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        Utils.HasGLError();
    }

    private void renderBlit() {
        Shader shader = shaders.get("blit");
        if (shader == null) {
            return;
        }

        GLES20.glUseProgram(shader.program);

        GLES20.glUniformMatrix4fv(shader.getUniform("mvpMatrix"), 1, false, FloatBuffer.wrap(renderProjection));
        GLES20.glUniform1i(shader.getUniform("texture"), 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, getTexture());

        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer);
        GLES20.glEnableVertexAttribArray(0);
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 8, textureBuffer);
        GLES20.glEnableVertexAttribArray(1);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        Utils.HasGLError();
    }

    public PaintingData getPaintingData(RectF rect, boolean undo) {
        int minX = (int) rect.left;
        int minY = (int) rect.top;
        int width = (int) rect.width();
        int height = (int) rect.height();

        GLES20.glGenFramebuffers(1, buffers, 0);
        int framebuffer = buffers[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);

        GLES20.glGenTextures(1, buffers, 0);
        int texture = buffers[0];

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_LINEAR);
        GLES20.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_LINEAR);
        GLES20.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_S, GL10.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_T, GL10.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);

        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0);

        GLES20.glViewport(0, 0, (int) size.width, (int) size.height);

        if (shaders == null) {
            return null;
        }
        Shader shader = shaders.get(undo ? "nonPremultipliedBlit" : "blit");
        if (shader == null) {
            return null;
        }
        GLES20.glUseProgram(shader.program);

        Matrix translate = new Matrix();
        translate.preTranslate(-minX, -minY);
        float effective[] = GLMatrix.LoadGraphicsMatrix(translate);
        float finalProjection[] = GLMatrix.MultiplyMat4f(projection, effective);

        GLES20.glUniformMatrix4fv(shader.getUniform("mvpMatrix"), 1, false, FloatBuffer.wrap(finalProjection));

        if (undo) {
            GLES20.glUniform1i(shader.getUniform("texture"), 0);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, getTexture());
        } else {
            GLES20.glUniform1i(shader.getUniform("texture"), 0);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bitmapTexture.texture());

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, getTexture());
        }
        GLES20.glClearColor(0, 0, 0, 0);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer);
        GLES20.glEnableVertexAttribArray(0);
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 8, textureBuffer);
        GLES20.glEnableVertexAttribArray(1);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        dataBuffer.limit(width * height * 4);
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, dataBuffer);

        PaintingData data;
        if (undo) {
            data = new PaintingData(null, dataBuffer);
        } else {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(dataBuffer);

            data = new PaintingData(bitmap, null);
        }

        buffers[0] = framebuffer;
        GLES20.glDeleteFramebuffers(1, buffers, 0);

        buffers[0] = texture;
        GLES20.glDeleteTextures(1, buffers, 0);

        return data;
    }

    public PaintingData getRecoverPaintingData(RectF rect){
        int minX = (int) rect.left;
        int minY = (int) rect.top;
        int width = (int) rect.width();
        int height = (int) rect.height();

        GLES20.glGenFramebuffers(1, recoverBuffers, 0);
        int framebuffer = recoverBuffers[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);

        GLES20.glGenTextures(1, recoverBuffers, 0);
        int texture = recoverBuffers[0];

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_LINEAR);
        GLES20.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_LINEAR);
        GLES20.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_S, GL10.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_T, GL10.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);

        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0);

        GLES20.glViewport(0, 0, (int) size.width, (int) size.height);

        if (shaders == null) {
            return null;
        }
//        Shader shader = shaders.get(undo ? "nonPremultipliedBlit" : "blit");
        Shader shader = shaders.get("nonPremultipliedBlit");
        if (shader == null) {
            return null;
        }
        GLES20.glUseProgram(shader.program);

        Matrix translate = new Matrix();
        translate.preTranslate(-minX, -minY);
        float effective[] = GLMatrix.LoadGraphicsMatrix(translate);
        float finalProjection[] = GLMatrix.MultiplyMat4f(projection, effective);

        GLES20.glUniformMatrix4fv(shader.getUniform("mvpMatrix"), 1, false, FloatBuffer.wrap(finalProjection));

//        if (undo) {
            GLES20.glUniform1i(shader.getUniform("texture"), 0);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, getTexture());
//        } else {
//            GLES20.glUniform1i(shader.getUniform("texture"), 0);
//
//            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
//            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bitmapTexture.texture());
//
//            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
//            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, getTexture());
//        }
        GLES20.glClearColor(0, 0, 0, 0);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer);
        GLES20.glEnableVertexAttribArray(0);
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 8, textureBuffer);
        GLES20.glEnableVertexAttribArray(1);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        recoverDataBuffer.limit(width * height * 4);
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, recoverDataBuffer);

        PaintingData data;
//        if (undo) {
//            data = new PaintingData(null, recoverDataBuffer);
//        } else {
//            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
//            bitmap.copyPixelsFromBuffer(recoverDataBuffer);

//            EventBus.getDefault().post(MessageWrap.getInstance(bitmap));
            data = new PaintingData(null, recoverDataBuffer);
//        }

        recoverBuffers[0] = framebuffer;
        GLES20.glDeleteFramebuffers(1, recoverBuffers, 0);

        recoverBuffers[0] = texture;
        GLES20.glDeleteTextures(1, recoverBuffers, 0);

//        recoverDataBuffer.clear();

        return data;
    }

    public void setBrush(Brush value) {
        // 给画刷赋值
        brush = value;
        // 把之前的画刷纹理清除掉
        if (brushTexture != null) {
            brushTexture.cleanResources(true);
            brushTexture = null;
        }
    }

    public boolean isPaused() {
        return paused;
    }

    public void onPause(final Runnable completionRunnable) {
        renderView.performInContext(new Runnable() {
            @Override
            public void run() {
                paused = true;
                PaintingData data = getPaintingData(getBounds(), true);
                backupSlice = new Slice(data.data, getBounds(), delegate.requestDispatchQueue());

                cleanResources(false);

                if (completionRunnable != null)
                    completionRunnable.run();
            }
        });
    }

    public void onResume() {
        restoreSlice(backupSlice);
        backupSlice = null;
        paused = false;
    }

    public void cleanResources(boolean recycle) {
        if (reusableFramebuffer != 0) {
            recoverBuffers[0] = reusableFramebuffer;
            GLES20.glDeleteFramebuffers(1, recoverBuffers, 0);
            reusableFramebuffer = 0;

            buffers[0] = reusableFramebuffer;
            GLES20.glDeleteFramebuffers(1, buffers, 0);
            reusableFramebuffer = 0;
        }

        bitmapTexture.cleanResources(recycle);

        if (paintTexture != 0) {
            recoverBuffers[0] = paintTexture;
            GLES20.glDeleteTextures(1, recoverBuffers, 0);

            buffers[0] = paintTexture;
            GLES20.glDeleteTextures(1, buffers, 0);
            paintTexture = 0;
        }

        if (brushTexture != null) {
            brushTexture.cleanResources(true);
            brushTexture = null;
        }

        if (shaders != null) {
            for (Shader shader : shaders.values()) {
                shader.cleanResources();
            }
            shaders = null;
        }
    }

    private int getReusableFramebuffer() {
        if (reusableFramebuffer == 0) {
            int[] buffers = new int[1];
            GLES20.glGenFramebuffers(1, buffers, 0);
            reusableFramebuffer = buffers[0];

            Utils.HasGLError();
        }
        return reusableFramebuffer;
    }

    private int getTexture() {
        if (bitmapTexture != null) {
            return bitmapTexture.texture();
        }
        return 0;
    }

    private int getPaintTexture() {
        if (paintTexture == 0) {
            paintTexture = Texture.generateTexture(size);
        }
        return paintTexture;
    }

    public void setupShaders() {
        shaders = ShaderSet.setup();
    }
}
