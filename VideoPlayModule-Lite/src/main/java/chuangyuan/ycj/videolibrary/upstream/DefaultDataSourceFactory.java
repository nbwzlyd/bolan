/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package chuangyuan.ycj.videolibrary.upstream;

import android.content.Context;
import androidx.annotation.Nullable;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSource.Factory;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.TransferListener;

/**
 * A {@link Factory} that produces {@link androidx.media3.datasource.DefaultDataSource} instances that delegate to {@link
 * androidx.media3.datasource.DefaultHttpDataSource}s for non-file/asset/content URIs.
 */
public final class DefaultDataSourceFactory implements Factory {

  private final Context context;
  @Nullable private final TransferListener listener;
  private final Factory baseDataSourceFactory;

  /**
   * Creates an instance.
   *
   * @param context A context.
   */
  public DefaultDataSourceFactory(Context context) {
    this(context, /* userAgent= */ (String) null, /* listener= */ null);
  }

  /**
   * Creates an instance.
   *
   * @param context A context.
   * @param userAgent The user agent that will be used when requesting remote data, or {@code null}
   *     to use the default user agent of the underlying platform.
   */
  public DefaultDataSourceFactory(Context context, @Nullable String userAgent) {
    this(context, userAgent, /* listener= */ null);
  }

  /**
   * Creates an instance.
   *
   * @param context A context.
   * @param userAgent The user agent that will be used when requesting remote data, or {@code null}
   *     to use the default user agent of the underlying platform.
   * @param listener An optional listener.
   */
  public DefaultDataSourceFactory(
      Context context, @Nullable String userAgent, @Nullable TransferListener listener) {
    this(context, listener, new DefaultHttpDataSource.Factory().setUserAgent(userAgent));
  }

  /**
   * Creates an instance.
   *
   * @param context A context.
   * @param baseDataSourceFactory A {@link Factory} to be used to create a base {@link DataSource}
   *     for {@link androidx.media3.datasource.DefaultDataSource}.
   * @see androidx.media3.datasource.DefaultDataSource#DefaultDataSource(Context, DataSource)
   */
  public DefaultDataSourceFactory(Context context, Factory baseDataSourceFactory) {
    this(context, /* listener= */ null, baseDataSourceFactory);
  }

  /**
   * Creates an instance.
   *
   * @param context A context.
   * @param listener An optional listener.
   * @param baseDataSourceFactory A {@link Factory} to be used to create a base {@link DataSource}
   *     for {@link androidx.media3.datasource.DefaultDataSource}.
   * @see androidx.media3.datasource.DefaultDataSource#DefaultDataSource(Context, DataSource)
   */
  public DefaultDataSourceFactory(
      Context context,
      @Nullable TransferListener listener,
      Factory baseDataSourceFactory) {
    this.context = context.getApplicationContext();
    this.listener = listener;
    this.baseDataSourceFactory = baseDataSourceFactory;
  }

  @Override
  public androidx.media3.datasource.DefaultDataSource createDataSource() {
    androidx.media3.datasource.DefaultDataSource dataSource =
        new DefaultDataSource(context, baseDataSourceFactory.createDataSource());
    if (listener != null) {
      dataSource.addTransferListener(listener);
    }
    return dataSource;
  }
}
