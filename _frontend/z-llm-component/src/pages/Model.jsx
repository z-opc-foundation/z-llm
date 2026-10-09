/** LLM 模型管理：/llm-center/model CRUD。 */
import {useEffect, useState} from 'react'
import {Button, Form, Input, InputNumber, message, Modal, Popconfirm, Select, Space, Table, Tag, Typography} from 'antd'
import {DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined} from '@ant-design/icons'
import {llmApi} from '../services/api'

const {Title, Paragraph} = Typography

export default function Model() {
    const [rows, setRows] = useState([])
    const [providers, setProviders] = useState([])
    const [loading, setLoading] = useState(false)
    const [editing, setEditing] = useState(null)
    const [form] = Form.useForm()

    const fetch = async () => {
        setLoading(true)
        try {
            const [m, p] = await Promise.all([llmApi.modelList(), llmApi.providerList()])
            const mList = Array.isArray(m) ? m : (m?.data || [])
            const pList = Array.isArray(p) ? p : (p?.data || [])
            setRows(mList)
            setProviders(pList)
        } catch (e) {
            message.error(e?.message || '加载失败')
        } finally { setLoading(false) }
    }
    useEffect(() => { fetch() }, [])

    const providerNameOf = (id) => providers.find(p => p.id === id)?.providerName || id

    const openNew = () => { setEditing({}); form.resetFields() }
    const openEdit = (row) => { setEditing(row); form.setFieldsValue(row) }

    const onSubmit = async () => {
        try {
            const values = await form.validateFields()
            if (editing?.id) await llmApi.modelUpdate({...values, id: editing.id})
            else await llmApi.modelCreate(values)
            message.success('已保存')
            setEditing(null)
            fetch()
        } catch (e) {
            if (e?.errorFields) return
            message.error(e?.message || '保存失败')
        }
    }

    const onDelete = async (row) => {
        try {
            await llmApi.modelDelete(row.id)
            message.success('已删除')
            fetch()
        } catch (e) { message.error(e?.message || '删除失败') }
    }

    const columns = [
        {title: '模型名', dataIndex: 'modelName', key: 'name', width: 200},
        {title: '模型编码', dataIndex: 'modelCode', key: 'code', width: 180,
            render: (v) => <Tag>{v}</Tag>},
        {title: '供应商', dataIndex: 'providerId', key: 'provider', width: 160,
            render: providerNameOf},
        {title: '上下文窗口', dataIndex: 'contextWindow', key: 'ctx', width: 120,
            render: (v) => v ? v.toLocaleString() : '—'},
        {title: '最大输出', dataIndex: 'maxOutput', key: 'out', width: 100,
            render: (v) => v || '—'},
        {title: '启用', dataIndex: 'enabled', key: 'enabled', width: 80,
            render: (v) => v ? <Tag color="green">启用</Tag> : <Tag color="default">停用</Tag>},
        {title: '操作', key: 'op', width: 160,
            render: (_, r) => (
                <Space size="small">
                    <Button size="small" icon={<EditOutlined/>} onClick={() => openEdit(r)}>编辑</Button>
                    <Popconfirm title={`删除模型 ${r.modelName}？`} onConfirm={() => onDelete(r)} okText="删除" cancelText="取消" okButtonProps={{danger: true}}>
                        <Button size="small" danger icon={<DeleteOutlined/>}>删除</Button>
                    </Popconfirm>
                </Space>
            )},
    ]

    return (
        <div>
            <Space style={{marginBottom: 16}}>
                <Title level={4} style={{margin: 0}}>LLM 模型</Title>
                <Button icon={<ReloadOutlined/>} onClick={fetch} loading={loading}>刷新</Button>
                <Button type="primary" icon={<PlusOutlined/>} onClick={openNew}>新增</Button>
            </Space>
            <Paragraph type="secondary">/llm-center/model/*：挂到供应商底下的具体模型定义。</Paragraph>

            <Table rowKey="id" dataSource={rows} columns={columns} loading={loading} size="small" pagination={false}/>

            <Modal title={editing?.id ? '编辑模型' : '新增模型'} open={!!editing} onCancel={() => setEditing(null)}
                   onOk={onSubmit} width={600} destroyOnClose>
                <Form form={form} layout="vertical">
                    <Form.Item name="modelName" label="模型名" rules={[{required: true, message: '请输入模型名'}]}>
                        <Input placeholder="如 MiniMax-M2"/>
                    </Form.Item>
                    <Form.Item name="modelCode" label="模型编码" rules={[{required: true, message: '请输入编码'}]}>
                        <Input placeholder="如 minimax-m2"/>
                    </Form.Item>
                    <Form.Item name="providerId" label="供应商" rules={[{required: true, message: '请选择供应商'}]}>
                        <Select placeholder="选择供应商">
                            {providers.map(p => <Select.Option key={p.id} value={p.id}>{p.providerName}</Select.Option>)}
                        </Select>
                    </Form.Item>
                    <Form.Item name="contextWindow" label="上下文窗口（tokens）">
                        <InputNumber min={0} style={{width: '100%'}}/>
                    </Form.Item>
                    <Form.Item name="maxOutput" label="最大输出（tokens）">
                        <InputNumber min={0} style={{width: '100%'}}/>
                    </Form.Item>
                    <Form.Item name="enabled" label="启用" initialValue={true}>
                        <Select>
                            <Select.Option value={true}>启用</Select.Option>
                            <Select.Option value={false}>停用</Select.Option>
                        </Select>
                    </Form.Item>
                </Form>
            </Modal>
        </div>
    )
}
