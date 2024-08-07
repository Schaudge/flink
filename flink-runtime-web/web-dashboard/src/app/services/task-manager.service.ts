/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { EMPTY, Observable, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';

import {
  JobMetric,
  MetricMap,
  TaskManagerDetail,
  TaskManagerList,
  TaskManagerLogItem,
  TaskManagerLogDetail,
  TaskManagersItem,
  TaskManagerThreadDump
} from '@flink-runtime-web/interfaces';
import { ProfilingDetail, ProfilingList } from '@flink-runtime-web/interfaces/job-profiler';

import { ConfigService } from './config.service';

@Injectable({
  providedIn: 'root'
})
export class TaskManagerService {
  constructor(private readonly httpClient: HttpClient, private readonly configService: ConfigService) {}

  loadManagers(): Observable<TaskManagersItem[]> {
    return this.httpClient.get<TaskManagerList>(`${this.configService.BASE_URL}/taskmanagers`).pipe(
      map(data => data.taskmanagers || []),
      catchError(() => of([]))
    );
  }

  loadManager(taskManagerId: string): Observable<TaskManagerDetail> {
    return this.httpClient
      .get<TaskManagerDetail>(`${this.configService.BASE_URL}/taskmanagers/${taskManagerId}`)
      .pipe(catchError(() => EMPTY));
  }

  loadLogList(taskManagerId: string): Observable<TaskManagerLogItem[]> {
    return this.httpClient
      .get<{ logs: TaskManagerLogItem[] }>(`${this.configService.BASE_URL}/taskmanagers/${taskManagerId}/logs`)
      .pipe(map(data => data.logs));
  }

  loadLog(taskManagerId: string, logName: string): Observable<TaskManagerLogDetail> {
    const url = `${this.configService.BASE_URL}/taskmanagers/${taskManagerId}/logs/${logName}`;
    return this.httpClient
      .get(url, { responseType: 'text', headers: new HttpHeaders().append('Cache-Control', 'no-cache') })
      .pipe(
        map(data => {
          return {
            data,
            url
          };
        })
      );
  }

  loadThreadDump(taskManagerId: string): Observable<string> {
    return this.httpClient
      .get<TaskManagerThreadDump>(`${this.configService.BASE_URL}/taskmanagers/${taskManagerId}/thread-dump`)
      .pipe(
        map(taskManagerThreadDump => {
          return taskManagerThreadDump.threadInfos.map(threadInfo => threadInfo.stringifiedThreadInfo).join('');
        })
      );
  }

  loadLogs(taskManagerId: string): Observable<string> {
    return this.httpClient.get(`${this.configService.BASE_URL}/taskmanagers/${taskManagerId}/log`, {
      responseType: 'text',
      headers: new HttpHeaders().append('Cache-Control', 'no-cache')
    });
  }

  loadStdout(taskManagerId: string): Observable<string> {
    return this.httpClient.get(`${this.configService.BASE_URL}/taskmanagers/${taskManagerId}/stdout`, {
      responseType: 'text',
      headers: new HttpHeaders().append('Cache-Control', 'no-cache')
    });
  }

  loadMetrics(taskManagerId: string, listOfMetricName: string[]): Observable<MetricMap> {
    const metricName = listOfMetricName.join(',');
    return this.httpClient
      .get<JobMetric[]>(`${this.configService.BASE_URL}/taskmanagers/${taskManagerId}/metrics`, {
        params: { get: metricName }
      })
      .pipe(
        map(arr => {
          const result: MetricMap = {};
          arr.forEach(item => {
            result[item.id] = parseFloat(item.value);
          });
          return result;
        })
      );
  }

  loadHistoryServerTaskManagerLogUrl(jobId: string, taskManagerId: string): Observable<string> {
    return this.httpClient
      .get<{ url: string }>(`${this.configService.BASE_URL}/jobs/${jobId}/taskmanagers/${taskManagerId}/log-url`)
      .pipe(map(data => data.url));
  }

  loadProfilingList(taskManagerId: string): Observable<ProfilingList> {
    return this.httpClient.get<ProfilingList>(`${this.configService.BASE_URL}/taskmanagers/${taskManagerId}/profiler`);
  }

  createProfilingInstance(taskManagerId: string, mode: string, duration: number): Observable<ProfilingDetail> {
    const requestParam = { mode, duration };
    return this.httpClient.post<ProfilingDetail>(
      `${this.configService.BASE_URL}/taskmanagers/${taskManagerId}/profiler`,
      requestParam
    );
  }

  loadProfilingResult(taskManagerId: string, filePath: string): Observable<Record<string, string>> {
    const url = `${this.configService.BASE_URL}/taskmanagers/${taskManagerId}/profiler/${filePath}`;
    return this.httpClient
      .get(url, { responseType: 'text', headers: new HttpHeaders().append('Cache-Control', 'no-cache') })
      .pipe(
        map(data => {
          return {
            data,
            url
          };
        })
      );
  }
}
