/**
 * z-llm API client：走 /llm-center/** 面（供应商/模型 CRUD）。
 */
import {createRequest} from '@yuku123/z-frontend-common'

const request = createRequest({baseURL: '', tokenKey: 'zllm_token'})

export default request

export function configureLlm(config) {
    if (config && config.apiBase !== undefined) {
        request.defaults.baseURL = config.apiBase
    }
}

export const llmApi = {
    providerList: () => request.get('/llm-center/provider/list'),
    providerCreate: (data) => request.post('/llm-center/provider/create', data),
    providerUpdate: (data) => request.post('/llm-center/provider/update', data),
    providerDelete: (id) => request.post('/llm-center/provider/delete', {id}),

    modelList: () => request.get('/llm-center/model/list'),
    modelCreate: (data) => request.post('/llm-center/model/create', data),
    modelUpdate: (data) => request.post('/llm-center/model/update', data),
    modelDelete: (id) => request.post('/llm-center/model/delete', {id}),
}
