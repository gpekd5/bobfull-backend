// #63 공통 K6 Harness — 공통 header·tag를 붙인 HTTP 요청 래퍼.

import http from 'k6/http';
import { BASE_URL, scenarioTags } from './config.js';

const JSON_HEADERS = { 'Content-Type': 'application/json' };

export function get(scenarioName, path, headers, tagName) {
    return http.get(`${BASE_URL}${path}`, {
        headers: headers || {},
        tags: scenarioTags(scenarioName, { name: tagName || scenarioName }),
    });
}

export function post(scenarioName, path, body, headers, tagName) {
    return http.post(`${BASE_URL}${path}`, JSON.stringify(body), {
        headers: Object.assign({}, JSON_HEADERS, headers || {}),
        tags: scenarioTags(scenarioName, { name: tagName || scenarioName }),
    });
}

export function parseData(res) {
    return JSON.parse(res.body).data;
}
