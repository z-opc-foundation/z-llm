/** LLM 供应商管理：/llm-center/provider CRUD。 */
import {useEffect, useState} from 'react'
import {Button, Form, Input, message, Modal, Popconfirm, Select, Space, Table, Tag, Typography} from 'antd'
import {DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined} from '@ant-design/icons'
import {llmApi} from '../services/api'

const {Title, Paragraph} = Typography

export default function Provider() {
    const [rows, setRows] = useState([])
    const [loading, setLoading] = useState(false)
    const [editing, setEditing] = useState(null) // null | {} 新增 | row 编辑
    const [form] = Form.useForm()

    const fetch = async () => {
        setLoading(true)
        try {
            const res = await llmApi.providerList()
            const list = Array.isArray(res) ? res : (res?.data || [])
            setRows(list)
        } catch (e) {
            message.error(e?.message || '加载失败')
        } finally { setLoading(false) }
    }
    useEffect(() => { fetch() }, [])

    const openNew = () => { setEditing({}); form.resetFields() }
    const openEdit = (row) => { setEditing(row); form.setFieldsValue(row) }

    const onSubmit = async () => {
        try {
            const values = await form.validateFields()
            if (editing?.id) await llmApi.providerUpdate({...values, id: editing.id})
            else await llmApi.providerCreate(values)
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
            await llmApi.providerDelete(row.id)
            message.success('已删除')
            fetch()
        } catch (e) { message.error(e?.message || '删除失败') }
    }

    const columns = [
        {title: '名称', dataIndex: 'providerName', key: 'name', width: 180},
        {title: '编码', dataIndex: 'providerCode', key: 'code', width: 140,
            render: (v) => <Tag>{v}</Tag>},
        {title: 'Base URL', dataIndex: 'baseUrl', key: 'url', ellipsis: true},
        {title: '协议', dataIndex: 'protocol', key: 'protocol', width: 110,
            render: (v) => <Tag color="blue">{v || 'OPENAI'}</Tag>},
        {title: '启用', dataIndex: 'enabled', key: 'enabled', width: 80,
            render: (v) => v ? <Tag color="green">启用</Tag> : <Tag color="default">停用</Tag>},
        {title: '操作', key: 'op', width: 160,
            render: (_, r) => (
                <Space size="small">
                    <Button size="small" icon={<EditOutlined/>} onClick={() => openEdit(r)}>编辑</Button>
                    <Popconfirm title={`删除供应商 ${r.providerName}？`} onConfirm={() => onDelete(r)} okText="删除" cancelText="取消" okButtonProps={{danger: true}}>
                        <Button size="small" danger icon={<DeleteOutlined/>}>删除</Button>
                    </Popconfirm>
                </Space>
            )},
    ]

    return (
        <div>
            <Space style={{marginBottom: 16}}>
                <Title level={4} style={{margin: 0}}>LLM 供应商</Title>
                <Button icon={<ReloadOutlined/>} onClick={fetch} loading={loading}>刷新</Button>
                <Button type="primary" icon={<PlusOutlined/>} onClick={openNew}>新增</Button>
            </Space>
            <Paragraph type="secondary">/llm-center/provider/*：API 网关型供应商接入配置（OpenAI 兼容协议为主）。</Paragraph>

            <Table rowKey="id" dataSource={rows} columns={columns} loading={loading} size="small" pagination={false}/>

            <Modal title={editing?.id ? '编辑供应商' : '新增供应商'} open={!!editing} onCancel={() => setEditing(null)}
                   onOk={onSubmit} width={600} destroyOnClose>
                <Form form={form} layout="vertical">
                    <Form.Item name="providerName" label="名称" rules={[{required: true, message: '请输入名称'}]}>
                        <Input placeholder="如 MiniMax 官方"/>
                    </Form.Item>
                    <Form.Item name="providerCode" label="编码" rules={[{required: true, message: '请输入编码'}]}>
                        <Input placeholder="如 minimax"/>
                    </Form.Item>
                    <Form.Item name="baseUrl" label="Base URL" rules={[{required: true, message: '请输入 Base URL'}]}>
                        <Input placeholder="https://api.minimax.chat/v1"/>
                    </Form.Item>
                    <Form.Item name="protocol" label="协议" initialValue="OPENAI">
                        <Select>
                            <Select.Option value="OPENAI">OPENAI 兼容</Select.Option>
                            <Select.Option value="NATIVE">原生</Select.Option>
                        </Select>
                    </Form.Item>
                    <Form.Item name="enabled" label="启用" valuePropName="checked" initialValue={true}>
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
